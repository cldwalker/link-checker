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

(defn fetch-issues
  "Fetches a public repo's issues by state (closed or open). Returns [] if issues are disabled."
  [user repo state]
  (let [ret (filter-bug (github-api-call issues/issues user repo (assoc (gh-auth) :state state :all-pages true)))]
    (if (= ret [[:message "Issues are disabled for this repo"]]) [] ret)))

(defn fetch-repos
  "Fetch all public repositories for a user"
  [user]
  (filter-bug (github-api-call user-repos user (assoc (gh-auth) :all-pages true))))

(defn ->repo
  "Calculates repo stats given api responses from open issues, closed issues and repo itself"
  [open closed repo]
  (let [all-issues (into open closed)
        total (count all-issues)
        comments (->> all-issues (map :comments) (apply +))
        days-to-resolve (->> closed
                             (map #(/ (difference-in-hours (:created_at %) (:closed_at %)) 24.0))
                             (apply +))]
    {:full-name (:full_name repo)
     :fork (:fork repo)
     :count-total total
     :count-closed (count closed)
     :count-open (count open)
     :count-answered (count (filter #(pos? (:comments %)) open))
     :count-pull-requests (count (filter #(get-in % [:pull_request :html_url]) all-issues))
     :count-days-to-resolve days-to-resolve
     :count-comments comments
     :last-issue-created-at (format-date (->> all-issues (sort-by :created_at) last :created_at))
     :comments-average (average comments (count all-issues))
     :last-pushed-at (format-date (:pushed_at repo))
     :stars (:watchers repo)
     :days-to-resolve-average (average days-to-resolve (count closed))}))

(defn fetch-repo-info
  "Fetches a repo's issues and extracts interesting stats from them"
  [user repo]
  (let [repo-name (get! repo :name)]
    (log/info :msg (format "Fetching repo info for %s/%s" user repo-name))
    (->repo (fetch-issues user repo-name "open")
            (fetch-issues user repo-name "closed")
            repo)))

(defn- fetch-specific-repo [user repo]
  (specific-repo user repo (gh-auth)))

(defn- active-forks
  "Filters active forks while trying to be accurate and minimizing number of api calls."
  [user forks]
  (->> forks
       (filter
        #(or (> (:watchers %) 1) (some-> (:open_issues %) pos?)))
       (filter
        #(let [repo (fetch-specific-repo user (:name %))]
           (or (not= (:name repo) (get-in repo [:parent :name]))
               ;; compare correctly handles these timestamps e.g. "2013-04-19T00:42:18Z"
               ((comp pos? compare) (:pushed_at repo) (get-in repo [:parent :pushed_at])))))))

(defn fetch-authored-repos-and-active-forks
  "Fetches a user's authored repos and active forks"
  [user]
  (as-> (fetch-repos user)
        repos
        (sort-by :name (concat (remove :fork repos) (active-forks user (filter :fork repos))))))


;;; Cache api calls for maximum reuse. Of course, this cache only lasts
;;; as long as the app lives.
(def memoized-fetch-repo-info (memoize fetch-repo-info))
(def memoized-fetch-authored-repos-and-active-forks (memoize fetch-authored-repos-and-active-forks))

(defn- render-haml
  [template repo-map]
  (haml/html
   (clostache/render-resource template repo-map)))

(defn calculate-total-row
  "Calculates total stats from stats collected from all repos."
  [repos]
  (let [active-repos (remove #(zero? (:count-total %)) repos)
        sum #(apply + (map (fn [e] (get! e %)) active-repos))
        percent #(when (pos? %2) (Math/round (float (* 100 (/ %1 %2)))))
        formatted-percent #(when-let [p (percent %1 %2)] (str "(" p "%)"))]
    (as-> {:count-total (sum :count-total)
           :count-closed (sum :count-closed)
           :count-open (sum :count-open)
           :count-answered (sum :count-answered)
           :count-pull-requests (sum :count-pull-requests)
           :comments-average (average (sum :count-comments) (sum :count-total))
           :days-to-resolve-average (average (sum :count-days-to-resolve) (sum :count-closed))}
          stats
          (assoc stats
            :percent-closed (formatted-percent (:count-closed stats) (:count-total stats))
            :percent-answered (formatted-percent (:count-answered stats) (:count-open stats))
            :percent-pull-requests (formatted-percent (:count-pull-requests stats) (:count-total stats))))))

(defn- sends-final-events
  "Sends a results event containing total row and message event summarizing user repositories."
  [send-to user repos]
  (send-to
   "results"
   (render-haml "public/total.haml" (calculate-total-row repos)))

  (send-to
   "message"
   (format
    "<a href=\"https://github.com/%s\">%s</a> has %s repositories: %s are authored and %s are active forks. <a href=\"#total-stats\">Their stats</a> are below."
    user
    user
    (count repos)
    (count (remove :fork repos))
    (count (filter :fork repos)))))

(defn- fetch-repo-and-send-row [send-to user repo]
  (let [repo-map (memoized-fetch-repo-info user repo)]
    (send-to "results" (render-haml "public/row.haml" repo-map))
    repo-map))

(defn- stream-repositories*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context user]
  (let [active-repos (memoized-fetch-authored-repos-and-active-forks user)
        send-to (partial send-event-fn sse-context)]
    (send-to "message"
             (format "%s has %s repositories. Fetching data... <img src='/images/spinner.gif' />"
                     user (count active-repos)))
    (->> active-repos
         (mapv (partial fetch-repo-and-send-row send-to user))
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
