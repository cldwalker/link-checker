(ns link-checker.check
  (:require [io.pedestal.service.log :as log]
            [clj-http.client :as client]
            [com.github.ragnard.hamelito.hiccup :as haml]
            [net.cgrand.enlive-html :as enlive]
            [link-checker.util :refer [calc-time shorten-to]]
            [clostache.parser :as clostache]
            [clojure.repl])
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
   (if (zero? (count links))
     "No links found. Check your link and selector."
     (let [invalid-links (count (remove #(= "200" (:status %)) links))]
       (format "%s. It took %ss to fetch %s links."
               (case invalid-links
                 0 "All links are valid!"
                 1 "1 link did not return a 200."
                 (str invalid-links " links did not return a 200."))
              time
              (count links))))))

(def default-clj-http-options
  {:max-redirects 5
   :throw-exceptions false
   :conn-timeout 3000
   :socket-timeout 4000})

(defn client-get [url]
  (log/info :msg (format "Thread %s: GET %s" (.. Thread currentThread getId) url))
  (client/get url default-clj-http-options))

(defn client-head [url]
  (log/info :msg (format "Thread %s: HEAD %s" (.. Thread currentThread getId) url))
  (client/head url default-clj-http-options))

(defn- fetch-link [url]
  (log/info :msg (format "Verifying link %s ..." url))
  (let [resp (try (let [head (client-head url)]
                    (if (> 400 (:status head) 199)
                      head
                      (client-get url)))
                  (catch Exception err {:error err}))
        status (if (:error resp) (str "Request failed: " (:error resp)) (str (:status resp)))]
    {:url url
     :shortened-url (shorten-to url 80)
     :status status
     :shortened-status (shorten-to status 40)
     :tr-class (if (= 200 (:status resp)) "success"
                   (if (:error resp) "failure" "no-success"))
     :response resp
     :thread-id (.. Thread currentThread getId)}) )

(def ^{:doc "Map of IDs to remaining links count"} link-counts (atom {}))

(defn- fetch-link-and-send-row [send-to url client-id link]
  (let [result-map (fetch-link link)]
    (when (get @link-counts client-id)
      (swap! link-counts update-in [client-id] dec)
      (when (zero? (rem (get @link-counts client-id) 5))
        (send-to "message" (format "Links remaining %s... <img src='/images/spinner.gif' />"  (get @link-counts client-id)))))
    (send-to "results" (render-haml "public/row.haml" result-map))
    result-map))

(defn- body->links
  [string options]
  (-> string
      (java.io.StringReader.)
      enlive/html-resource
      (enlive/select (if (seq (:selector options))
                       [(keyword (:selector options)) :a] [:a]))
      (as-> enlive-maps (map
                         #(get-in % [:attrs :href])
                         enlive-maps))))

(defn- invalid-link?
  [link]
  (or (contains? #{nil "" "#"} link)
      (re-find #"^(git:|javascript:|irc:|mailto:)" link)))

;;; thanks to alida's util.cl
(defn- expand-relative-links
  [url links]
  (let [jurl (URL. url)]
    (map #(str (URL. jurl %)) links)))

(defn- url->links
  [url options]
  (let [resp (try
               (client-get url)
               (catch Exception e nil))]
    (when (= 200 (:status resp))
      (expand-relative-links
       url
       (->> options
            (body->links (:body resp))
            distinct
            (remove invalid-link?))))))

(defn- valid-selector?
  [selector]
  (re-find #"^[\.#a-zA-Z0-9_-]+$" selector))

(defn- stream-links*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context url options]
  (let [send-to (partial send-event-fn sse-context)
        selector (clojure.string/trim (:selector options))]
    (if (and (seq selector) (not (valid-selector? selector)))
      (send-to "error" "Selector is invalid. Try again.")
      (if-let [links (url->links url (assoc options :selector selector))]
        (do
          (swap! link-counts assoc (:client-id options) (count links))
          (send-to "message"
                   (format "%s has %s links. Fetching data... <img src='/images/spinner.gif' />"
                           url (count links)))
          (let [start-time (System/currentTimeMillis)
                link-results (doall (pmap (partial fetch-link-and-send-row send-to url (:client-id options))
                                         links))]
           (send-final-message send-to (calc-time start-time) link-results))
          (send-to "end-message" (str "result?url=" url
                                      (if (seq (:selector options))
                                        (str "&selector=" (:selector options)) ""))))
        (send-to "error" "Unable to fetch the given url.")))))

(defn stream-links
  "Streams a url's verified links with a given fn and sse-context."
  [send-event-fn sse-context url options]
  (if url
    (try
      (stream-links* send-event-fn sse-context url options)
      {:status 200}
      (catch Exception exception
        (log/error :msg (str "Unexpected error: " exception))
        (clojure.repl/pst exception 25) ;; should log instead of print
        (send-event-fn sse-context "error" "An unexpected error occurred. :(")))
    (log/error :msg "No url given to verify links. Ignored.")))