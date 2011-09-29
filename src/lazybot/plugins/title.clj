;; The result of a team effort between programble and Rayne.
(ns lazybot.plugins.title
  (:use [lazybot info registry utilities]
        (clojure.contrib [string :only [ltrim]]
                         [io :only [reader]]
                         [logging :only [debug]])
        [clojail.core :only [thunk-timeout]])
  (:import java.util.concurrent.TimeoutException
           org.apache.commons.lang.StringEscapeUtils))

(def titlere #"(?i)<title>([^<]+)</title>")

(defn collapse-whitespace [s]
  (->> s (.split #"\s+") (interpose " ") (apply str)))

(defn add-url-prefix [url]
  (if-not (.startsWith url "http")
    (str "http://" url)
    url))

(defn slurp-or-default [url]
  (try
    (with-open [readerurl (reader url)]
      (let [acc (StringBuilder.)]
        (loop [lines (line-seq readerurl)]
          (when-let [[line & lines] (seq lines)]
            (.append acc line)
            (if (re-find #"(?i)</title>" line)
              (->> acc str (re-find titlere))
              (recur lines))))))
    (catch Exception _ nil)))

(defn url-blacklist-words [com bot] (:url-blacklist ((:config @bot) (:server @com))))

(defn url-check [com bot url]
  (some #(.contains url %) (url-blacklist-words com bot)))

(defn is-blacklisted? [[match-this not-this] s]
  (let [lower-s (.toLowerCase s)
        regex (if (seq not-this)
                (re-pattern (format "(?=.*%s(?!%s))^(\\w+)" match-this not-this))
                (re-pattern match-this))]
    (re-find regex lower-s)))

(defn strip-tilde [s] (apply str (remove #{\~} s)))

(defn check-blacklist [server user bot]
  (let [blacklist (:user-ignore-url-blacklist ((:config @bot) server))]
    (some (comp not nil?) (map
                           #(is-blacklisted? % (strip-tilde user))
                           blacklist))))

(defn title [{:keys [com nick bot user channel] :as com-m}
             links & {verbose? :verbose?}]
  (if (or (and verbose? (seq links))
          (and (not (check-blacklist (:server @com) user bot))
               (not (contains? (:channel-catch-blacklist ((:config @bot) (:server @com)))
                               channel))))
    (doseq [link (take 1 links)]
      (try
       (thunk-timeout #(let [url (add-url-prefix link)
                             page (slurp-or-default url)
                             match (second page)]
                         (if (and (seq page) (seq match) (not (url-check com bot url)))
                           (send-message com-m
                                              (str "\""
                                                   (ltrim
                                                    (StringEscapeUtils/unescapeHtml
                                                     (collapse-whitespace match)))
                                                   "\""))
                           (when verbose? (send-message com-m "Page has no title."))))
                      20 :sec)
       (catch TimeoutException _
         (when verbose?
           (send-message com-m "It's taking too long to find the title. I'm giving up.")))))
    (when verbose? (send-message com-m "Which page?"))))

(defplugin :irc
  (:hook
   :on-message
   (fn [{:keys [com bot nick channel message] :as com-m}]
     (let [info (:config @bot)
           get-links (fn [s]
                       (->> s
                            (re-seq #"(https?://|www\.)[^\]\[(){}\"'$^\s]+")
                            (map first)))]
       (when-not (contains? (:user-blacklist (info (:server @com))) nick)
         (let [prepend (:prepends info)
               links (get-links message)
               title-links? (and (not (m-starts-with message (:prepends info)))
                                 (:catch-links? (info (:server @com)))
                                 (seq links))]
           (debug (pr-str links))
           (when title-links?
             (title com-m links)))))))

  (:cmd
   "Gets the title of a web page. Takes a link. This is verbose, and prints error messages."
   #{"title"} (fn [com-m] (title com-m (:args com-m) :verbose? true))))