(ns link-checker.check
  (:require [io.pedestal.service.log :as log]
            [clj-http.client :as client]
            [com.github.ragnard.hamelito.hiccup :as haml]
            [net.cgrand.enlive-html :as enlive]
            [link-checker.util :refer [calc-time]]
            [clostache.parser :as clostache])
  (:import [java.net URL]))

(defn- render-haml
  [template repo-map]
  (haml/html
   (clostache/render-resource template repo-map)))

(defn- send-final-message
  "Sends a results event containing total row and message event summarizing checked links."
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
   :conn-timeout 3000
   :socket-timeout 3000})

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

(defn- invalid-link?
  [link]
  (or (contains? #{nil "" "#"} link)
      (re-find #"^(javascript:|irc:)" link)))

;;; thanks to alida's util.cl
(defn- expand-relative-links
  [url links]
  (let [jurl (URL. url)]
    (map #(str (URL. jurl %)) links)))

(defn- url->links
  [url]
  (let [resp (try
               (client/get url default-clj-http-options)
               (catch Exception e nil))]
    (when (= 200 (:status resp))
      (expand-relative-links
       url
       (->> (:body resp)
            body->links
            distinct
            (remove invalid-link?))))))

(defn- stream-links*
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

(defn stream-links
  "Streams a url's verified links with a given fn and sse-context."
  [send-event-fn sse-context url]
  (if url
    (try
      (stream-links* send-event-fn sse-context url)
      {:status 200}
      (catch Exception exception
        (log/error :msg (str "Unexpected error: " exception))
        (send-event-fn sse-context "error" "An unexpected error occurred. :(")))
    (log/error :msg "No url given to verify links. Ignored.")))