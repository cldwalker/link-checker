(ns link-checker.github
  (:require [tentacles.repos :refer [user-repos specific-repo]]
            [tentacles.issues :as issues]
            [io.pedestal.service.log :as log]
            [com.github.ragnard.hamelito.hiccup :as haml]
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

(defn- sends-final-events
  "Sends a results event containing total row and message event summarizing user repositories."
  [send-to user links]
  (send-to
   "message"
   (format "%s of the links are on github."
           (count (filter #(re-find #"github.com" (:url %)) links)))))

(defn- fetch-link [url]
  (log/info :msg (format "Fetching link %s" url))
  (let [status (try (slurp url) 200
                    (catch Exception e 404))]
    {:url url :status status :thread-id (.. Thread currentThread getId)}) )

(defn- fetch-link-and-send-row [send-to user url]
  (let [result-map (fetch-link url)]
    (send-to "results" (render-haml "public/row.haml" result-map))
    result-map))

(defn- stream-repositories*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context user]
  (let [links (->>  (fetch-repos user) (filter (comp seq :homepage)) (map :homepage))
        send-to (partial send-event-fn sse-context)]
    (send-to "message"
             (format "%s has %s links. Fetching data... <img src='/images/spinner.gif' />"
                     user (count links)))
    (->> links
         (pmap (partial fetch-link-and-send-row send-to user))
         (sends-final-events send-to user))
    (send-to "end-message" user)))

(defn stream-repositories
  "Streams a user's repositories with a given fn and sse-context."
  [send-event-fn sse-context user]
  (if user
    (try
      (stream-repositories* send-event-fn sse-context user)
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
    (log/error :msg "No user given to fetch repositories. Ignored.")))