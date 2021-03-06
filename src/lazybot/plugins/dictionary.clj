(ns lazybot.plugins.dictionary
  (:use [lazybot registry]
	[clojure-http.client :only [add-query-params]]
        [clojure.contrib.json :only [read-json]])
  (:require [clojure-http.resourcefully :as res])
  (:import java.net.URI))

(defn extract-stuff [js]
  (let [text (:text js)]
    [(.replaceAll (if (seq text) text "") "\\<.*?\\>" "") (:partOfSpeech js)]))

(defn lookup-def [key word]
  (-> (res/get
       (add-query-params (str "http://api.wordnik.com/api/word.json/" word "/definitions")
                         {"count" "1" "useCanonical" "true"})
       {"api_key" key})
      :body-seq first read-json first extract-stuff))

(defplugin 
  (:cmd
   "Takes a word and look's up it's definition via the Wordnik dictionary API." 
   #{"dict"} 
   (fn [{:keys [bot channel nick args]:as com-m}]
     (send-message com-m 
                   (prefix bot nick 
                        (let [[text part] (lookup-def (:wordnik-key (:config @bot)) (first args))]
                          (if (seq text) (str part ": " text) "Word not found.")))))))