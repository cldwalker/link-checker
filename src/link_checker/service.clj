(ns link-checker.service
    (:require [io.pedestal.service.http :as bootstrap]
              [io.pedestal.service.http.route :as route]
              [io.pedestal.service.http.body-params :as body-params]
              [io.pedestal.service.http.route.definition :refer [defroutes]]
              [io.pedestal.service.http.sse :as sse]
              [io.pedestal.service.log :as log]
              [com.github.ragnard.hamelito.hiccup :as haml]
              [clojure.java.io :as io]
              [link-checker.check :refer [stream-links]]
              [link-checker.util :refer [get-in!]]
              [ring.util.response :as ring-resp]))

(defn home-page
  [request]
  (ring-resp/response
   (haml/html (slurp (io/resource "public/index.haml")))))

(def ^{:doc "Map of IDs to SSE contexts"} subscribers (atom {}))

(defn links-page
  "Saves sse-context for streaming."
  [sse-context]
  (if-let [id (get-in sse-context [:request :query-params :id])]
    (swap! subscribers assoc id sse-context)
    (log/error :msg "No id passed to /links. Ignored.")))

(defn- get-sse-context
  "If sse-context doesn't exist yet, sleep and try again. This was needed for safari and
opening a result url in a new tab."
  [id]
  (if-let [sse-context (get @subscribers id)]
    sse-context
    (do
      (log/info :msg "Waiting 500ms for sse-context to start.")
      (Thread/sleep 500)
      (get @subscribers id))))

(defn stream-links-page
  "Stream links to the given client id."
  [request]
  (if-let [id (get-in request [:form-params "id"])]
    (if-let [sse-context (get-sse-context id)]
      (stream-links sse/send-event sse-context (get-in! request [:form-params "url"])
                    {:selector (get-in request [:form-params "selector"]) :client-id id})
      (log/error :msg (str "No sse context for id " id)))
    (log/error :msg "No id passed to stream links. Ignored.")))

(defroutes routes
  [[["/"
     ^:interceptors [(body-params/body-params) bootstrap/html-body]
     ["/links" {:get [::links (sse/start-event-stream links-page)]
                        :post stream-links-page}]
     ["/" {:get home-page}]
     ["/result" {:get [::results home-page]}]]]])

;; You can use this fn or a per-request fn via io.pedestal.service.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by link-checker.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::boostrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"

              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ;;::bootstrap/host "localhost"
              ::bootstrap/type :jetty
              ::bootstrap/port (Integer. (or (System/getenv "PORT") 8080))})
