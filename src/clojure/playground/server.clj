(ns clojure.playground.server
  (:require ; [reitit.http.interceptors.dev :as dev]
 ; [reitit.http.spec :as spec]
 ; [reitit.http.spec :as spec]
 ; [spec-tools.spell :as spell]
 ;; Uncomment to use
 ; [reitit.http.interceptors.dev :as dev]
   [clojure.core.async :as a]
   [clojure.string :as string]
   [io.pedestal.http :as server]
   [muuntaja.core :as m]
   [reitit.coercion.spec]
   [reitit.dev.pretty :as pretty]
   [reitit.http :as http]
   [reitit.http.coercion :as coercion]
   [reitit.http.interceptors.exception :as exception]
   [reitit.http.interceptors.multipart :as multipart] ;; Uncomment to use
   [reitit.http.interceptors.muuntaja :as muuntaja]
   [reitit.http.interceptors.parameters :as parameters]
   [reitit.pedestal :as pedestal]
   [reitit.swagger :as swagger]))

(defn interceptor [number]
  {:enter (fn [ctx] (a/go (update-in ctx [:request :number] (fnil + 0) number)))})

(defn hosting-interceptor
  []
  {:name :hosting-redirect
   :enter (fn [{:keys [request] :as context}]
            (let [host (-> request :headers (get "host"))
                  is-local-dev (string/includes? (-> request :headers (get "host")) ":")
                  incoming-host (if is-local-dev
                                  (-> host
                                      (string/split  #":")
                                      (first))
                                  host)
                  expected-host (-> request :reitit.core/match :data :host)]
              (if (= incoming-host expected-host)
                context
                (throw (ex-info "Invalid host" {:status 403
                                                :expected-host expected-host
                                                :host incoming-host})))))})

(def router
  (pedestal/routing-interceptor
   (http/router
    [""
     ["/test"
      ;; this will be showing only on matheusfrancisco:3000
      {:host "localhost"}
      ["/interceptors"
       {:swagger {:tags ["interceptors"]}
        :interceptors [(interceptor 1)]}
       ["/number2"
        {:interceptors [(interceptor 10)]
         :post {:interceptors [(interceptor 100)]
                :handler (fn [req]
                           {:status 200
                            :body (select-keys req [:number])})}}]
       ["/number"
        {:interceptors [(interceptor 10)]
         :get {:interceptors [(interceptor 100)]
               :handler (fn [req]
                          {:status 200
                           :body (select-keys req [:number])})}}]]]
     ["/test/v1"
      ;; this will be showing only on matheusfrancisco:3000
      {:host "matheusfrancisco"}
      ["/number"
       {:interceptors [(interceptor 10)]
        :get {:interceptors [(interceptor 100)]
              :handler (fn [req]
                         {:status 200
                          :body (select-keys req [:number])})}}]]]
    {;:reitit.interceptor/transform dev/print-context-diffs ;; pretty context diffs
       ;;:validate spec/validate ;; enable spec validation for route data
       ;;:reitit.spec/wrap spell/closed ;; strict top-level validation
     :exception pretty/exception
     :data {:coercion reitit.coercion.spec/coercion
            :muuntaja m/instance
            :interceptors [;; swagger feature
                           swagger/swagger-feature
                           (hosting-interceptor)
                             ;; query-params & form-params
                           (parameters/parameters-interceptor)
                             ;; content-negotiation
                           (muuntaja/format-negotiate-interceptor)
                             ;; encoding response body
                           (muuntaja/format-response-interceptor)
                             ;; exception handling
                           (exception/exception-interceptor)
                             ;; decoding request body
                           (muuntaja/format-request-interceptor)
                             ;; coercing response bodys
                           (coercion/coerce-response-interceptor)
                             ;; coercing request parameters
                           (coercion/coerce-request-interceptor)
                             ;; multipart
                           (multipart/multipart-interceptor)]}})))

(defn start []
  (println "server running in port 3000")
  (-> {:env :dev
       ::server/type :jetty
       ::server/port 3000
       ::server/join? false
       ;; no pedestal routes
       ::server/routes []
       ;; allow serving the swagger-ui styles & scripts from self
       ::server/secure-headers {:content-security-policy-settings
                                {:default-src "'self'"
                                 :style-src "'self' 'unsafe-inline'"
                                 :script-src "'self' 'unsafe-inline'"}}}
      (server/default-interceptors)
      ;; use the reitit router
      (pedestal/replace-last-interceptor router)
      (server/dev-interceptors)
      (server/create-server)
      (server/start)))

(comment
  (def s (start))
  (server/stop s)
  ;
  )

