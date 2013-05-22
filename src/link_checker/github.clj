(ns link-checker.github
  (:require [tentacles.repos :refer [user-repos specific-repo]]
            [tentacles.issues :as issues]
            [io.pedestal.service.log :as log]
            [clj-http.client :as client]
            [com.github.ragnard.hamelito.hiccup :as haml]
            [net.cgrand.enlive-html :as enlive]
            [link-checker.util :refer [get! average difference-in-hours format-date]]
            [clostache.parser :as clostache]))

;;; helpers
(defn gh-auth
  "Sets github authentication using $GITHUB_AUTH. Its value can be a basic auth user:pass
or an oauth token."
  []
  (if-let [auth (System/getenv "GITHUB_AUTH")]
    (if (.contains auth ":") {:auth auth} {:oauth-token auth})
    (or (throw (ex-info "Set $GITHUB_AUTH to an oauth token or basic auth in order to use Github's api." {})))))

;;; API calls
(defn- github-api-call
  "Wraps github api calls to handle unsuccessful responses."
  [f & args]
  (let [response (apply f args)]
    (if (some #{403 404} [(:status response)])
      (throw (ex-info
              (if (= 403 (:status response))
                "Rate limit has been exceeded for Github's API. Please try again later."
                (format "Received a %s from Github. Please try again later." (:status response)))
              {:reason :github-client-error :response response}))
      response)))


(defn- filter-bug
  "Eliminates tentacles bug, extra {} after each call"
  [s]
  (vec (filter seq s)))

(defn fetch-repos
  "Fetch all public repositories for a user"
  [user]
  (filter-bug (github-api-call user-repos user (assoc (gh-auth) :all-pages true))))

(defn- render-haml
  [template repo-map]
  (haml/html
   (clostache/render-resource template repo-map)))

(defn calc-time
  [start-time]
  (->> (/ (- (System/currentTimeMillis) start-time) 1000)
       float
       (format "%.2f")))

(defn- send-final-message
  "Sends a results event containing total row and message event summarizing user repositories."
  [send-to time links]
  (send-to
   "message"
   (format "Took %ss to fetch %s links. %s links did not return a 200."
           time
           (count links)
           (count (remove #(= 200 (:status %)) links)))))

(def default-clj-http-options
  {:max-redirects 5
   :throw-exceptions false
   :conn-timeout 100
   :socket-timeout 1000})

(defn- fetch-link [url]
  (log/info :msg (format "Fetching link %s" url))
  (let [resp (try (let [head (client/head url default-clj-http-options)]
                    (if (> 400 (:status head) 199)
                      head
                      (client/get url default-clj-http-options)))
                    (catch Exception err {:error err}))]
    {:url url
     :status (or (:error resp) (:status resp))
     :response resp
     :thread-id (.. Thread currentThread getId)}) )

(defn- fetch-link-and-send-row [send-to user url]
  (let [result-map (fetch-link url)]
    (send-to "results" (render-haml "public/row.haml" result-map))
    result-map))

(defn- body->links
  [string]
  (-> string
      (java.io.StringReader.)
      enlive/html-resource
      (enlive/select [:a])
      (as-> enlive-maps (map
                         #(get-in % [:attrs :href])
                         enlive-maps))))

(defn- url->links
  [url]
  (let [resp (try
               (client/get url default-clj-http-options)
               (catch Exception e nil))]
    (when (= 200 (:status resp))
      (filter #(re-find #"^http" (str %)) (doto (body->links (:body resp)) prn)))))

(defn- stream-repositories*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context user]
  (let [send-to (partial send-event-fn sse-context)]
    (if-let [links (url->links user)]
      (do
        (send-to "message"
                 (format "%s has %s links. Fetching data... <img src='/images/spinner.gif' />"
                         user (count links)))
        (let [start-time (System/currentTimeMillis)
              link-results (doall (pmap (partial fetch-link-and-send-row send-to user) links))]
          (send-final-message send-to (calc-time start-time) link-results))
        (send-to "end-message" (str "result?url=" user)))
      (send-to "error" "Unable to fetch given url."))))

(defn stream-repositories
  "Streams a url's verified links with a given fn and sse-context."
  [send-event-fn sse-context url]
  (if url
    (try
      (stream-repositories* send-event-fn sse-context url)
      {:status 200}
      (catch clojure.lang.ExceptionInfo exception
        (log/error :msg (str "40X response from Github: " (pr-str (ex-data exception))))
        (send-event-fn sse-context "error"
                       (if (= :github-client-error (:reason (ex-data exception)))
                         (.getMessage exception)
                         "An unexpected error occurred while contacting Github. Please try again later.")))
      (catch Exception exception
        (log/error :msg (str "Unexpected error: " exception))
        (send-event-fn sse-context "error" "An unexpected error occurred. :(")))
    (log/error :msg "No url given to verify links. Ignored.")))