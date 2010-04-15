;; The result of a team effort between programble and Rayne.
(ns sexpbot.plugins.title
  (:use [sexpbot info respond utilities]
	[clojure.contrib.duck-streams :only [reader]])
  (:import java.util.concurrent.TimeoutException))

(def titlere #"(?i)<title>([^<]+)</title>")

(defn collapse-whitespace [s]
  (->> s (.split #"\s+") (interpose " ") (apply str)))

(defn add-url-prefix [url]
  (if-not (.startsWith url "http://")
    (str "http://" url)
    url))

(defn slurp-or-default [url]
  (try
   (with-open [readerurl (reader url)]
     (some #(re-find titlere %) (line-seq readerurl)))   
   (catch java.lang.Exception e nil)))

(def url-blacklist-words ((read-config) :url-blacklist))

(defn url-check [url]
  (some #(.contains url %) url-blacklist-words))

(defn is-blacklisted? [[match-this not-this] s]
  (let [lower-s (.toLowerCase s)
	regex (if (seq not-this)
		(re-pattern (format "(?=.*%s(?!%s))^(\\w+)" match-this not-this))
		(re-pattern match-this))]
    (re-find regex lower-s)))

(defn strip-tilde [[target & more :as all]] (if (= target \~) (apply str more) all))

(defn check-blacklist [server sender login host]
  (let [blacklist (((read-config) :user-ignore-url-blacklist) server)]
    (some (comp not nil?) (map 
			   #(is-blacklisted? % (str sender "!" (strip-tilde login) "@" host)) 
			   blacklist))))

(defmethod respond :title* [{:keys [bot sender server channel login host args verbose?]}]
  (if (or (and verbose? (seq args)) 
	  (and (seq args) 
	       (not (check-blacklist server sender login host))
	       (not ((((read-config) :channel-catch-blacklist) server) channel))))
    (doseq [link (take 1 args)]
      (try
       (thunk-timeout #(let [url (add-url-prefix link)
			     page (slurp-or-default url)
			     match (second page)]
			 (if (and (seq page) (seq match) (not (url-check url)))
			   (.sendMessage bot channel (collapse-whitespace match))
			   (when verbose? (.sendMessage bot channel "Page has no title."))))
		      20)
       (catch TimeoutException _ 
	 (when verbose? (.sendMessage bot channel "It's taking too long to find the title. I'm giving up.")))))
    (when verbose? (.sendMessage bot channel "Which page?"))))

(defmethod respond :title2 [botmap] (respond (assoc botmap :command "title*")))
(defmethod respond :title [botmap] (respond (assoc botmap :command "title*" :verbose? true)))

(defplugin
  {"title"  :title
   "title2" :title2
   "title*" :title*})