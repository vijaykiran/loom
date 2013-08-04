(ns ^{:doc "Defines an interface between SSA form from core.async and Loom."
      :author "Aysylu"}
  loom.ssa
  (:require [clojure.set :as set]
            [clojure.string :as str]
            loom.io
            clojure.pprint
            [loom.graph :as g])
  (:use [clojure.core.async.impl.ioc-macros :only [parse-to-state-machine]]))

(defn ssa->loom
  "Converts the SSA form generated by core.async into Loom representation."
  ([ssa node-fn edge-fn]
   (let [nodes (delay (node-fn ssa))
         edges (delay (edge-fn ssa))]
     {:graph (reify
               g/Graph
               (nodes [g] @nodes)
               (edges [g] @edges)
               (has-node? [g node] (contains? @nodes node))
               (has-edge? [g n1 n2] (contains? @edges [n1 n2]))
               (neighbors [g] (partial g/neighbors g))
               (neighbors [g node] (->>
                                     @edges 
                                     (filter (fn [[n1 n2]] (= n1 node)))
                                     (map second)))
               (degree [g node] (count (g/neighbors g node)))
               g/Digraph
               (incoming [g] (partial g/incoming g))
               (incoming [g node] (->>
                                    @edges   
                                    (filter (fn [[n1 n2]] (= n2 node)))
                                    (map first)))
               (in-degree [g node] (count (g/incoming g node))))
      :data ssa})))

(defn ssa-nodes-fn
  "Returns basic blocks as nodes in SSA form."
  [ssa]
  (-> ssa
       keys
       set))

(defn ssa-edges-fn
  "Returns edges between basic blocks in SSA form."
  [ssa]
  (reduce
    (fn [edges [block instrs]]
      (let [last-instr (last instrs)
            {to :block
             then :then-block
             else :else-block} last-instr]
        (cond
          to (conj edges [block to])
          then (conj edges [block then] [block else])
          :else edges)))
    #{} ssa)) 

(defn ssa-node-label
  [ssa]
  (reduce
    (fn [coll [block instrs]]
      (conj coll
            {block [block
                    (->> instrs
                         (map (partial into {}))
                         (str/join "\n"))]}))
    {} ssa))

(defn view [{graph :graph data :data}]
  (loom.io/view graph
                :node-label (ssa-node-label data)))

(def ssa
  (->
    (parse-to-state-machine
      '[(loop [x 3]
            (cond
              (= x 1) (recur 1)
              (= x 3) (recur 2)
              :else (recur 3))
           )])
    second
    :blocks))

#_(clojure.pprint/pprint ssa)

#_(view (ssa->loom ssa ssa-nodes-fn ssa-edges-fn))

(let [[node & worklist] (into clojure.lang.PersistentQueue/EMPTY [1 2 3 4])]
  (println node (class worklist))) 

(defn dataflow-analysis
  "Performs dataflow analysis.
   Nodes have value nil initially."
  [& {:keys [start graph join transfer]}]
  (let [start (cond
                (set? start) start
                (coll? start) (set start)
                :else #{start})]
    (loop [out-values {} 
           [node & worklist] (into clojure.lang.PersistentQueue/EMPTY start)]
      (let [in-value (join (mapv out-values (g/incoming graph node)))
            out (transfer node in-value)
            update? (not= out (get out-values node))
            out-values (if update?
                         (assoc out-values node out)
                         out-values)
            worklist (if update?
                       (into worklist (g/neighbors graph node))
                       worklist)]
        (if (seq worklist)
          (recur out-values worklist)
          out-values)))))

#_(defn global-cse
  [graph node-data start]
  (letfn [(pure? [instr] (contains? instr :refs))
          (global-cse-join
            [values]
            (apply set/intersection values))
          (global-cse-transfer
            [node in-value]
            (into in-value (filter pure? (node-data node))))]
    (dataflow-analysis
      :start start
      :graph graph
      :join global-cse-join
      :transfer global-cse-transfer)))

#_(defn global-cse
  [ssa]
  (let [{graph :graph node-data :data} (ssa->loom (:blocks ssa) ssa-nodes-fn ssa-edges-fn)
        start (:start-block ssa)]
    (letfn [(pure? [instr] (contains? instr :refs))
          (global-cse-join
            [values]
            (if (seq values)
              (apply set/intersection values)
              #{}))
          (global-cse-transfer
            [node in-value]
            (into in-value (map :refs (filter pure? (node-data node)))))]
    (dataflow-analysis
      :start start
      :graph graph
      :join global-cse-join
      :transfer global-cse-transfer))))

(defn global-cse
  [ssa]
  (let [{graph :graph node-data :data} (ssa->loom (:blocks ssa) ssa-nodes-fn ssa-edges-fn)
        start (:start-block ssa)]
    (letfn [(pure? [instr] (contains? instr :refs))
          (global-cse-join
            [values]
            (if (seq values)
              (apply set/intersection values)
              #{}))
          (global-cse-transfer
            [node in-value]
            (into in-value (map :refs (filter pure? (node-data node)))))]
    (dataflow-analysis
      :start start
      :graph graph
      :join global-cse-join
      :transfer global-cse-transfer))))

(defonce test
  (second
    (parse-to-state-machine '[(if (> x 0)
                                (> x 0)
                                (> x 3))])))  

(def graph (:graph (ssa->loom (:blocks test) ssa-nodes-fn ssa-edges-fn)))
(def data (:data (ssa->loom (:blocks test) ssa-nodes-fn ssa-edges-fn)))

(defn find-cse-in-block
  [block out-values predecessors]
  (let [in-value (if (seq predecessors)
                   (apply set/intersection (map out-values predecessors))
                   #{})]
    (reduce
      (fn [new-block instr]
        (if (contains? in-value (:refs instr))
          (conj new-block (assoc instr :repeated true))
          (conj new-block (assoc instr :repeated false))))
      [] block)))

(clojure.pprint/pprint test)
(clojure.pprint/pprint
  (global-cse test))

(clojure.pprint/pprint
  (for [n (g/nodes graph)
        :let [ps (g/incoming graph n)
              out-values (global-cse test)]]
    [n (find-cse-in-block (get data n) out-values ps)]))

(clojure.pprint/pprint data)

(clojure.pprint/pprint
  (find-cse-in-block
    [{:refs [clojure.core/inc 7]}
     {:refs [clojure.core/+ 1]}]
    {1 #{[clojure.core/inc 7]
         [clojure.core/+ 2]}}
    [1]))

(clojure.pprint/pprint
  (find-cse-in-block
    [{:refs [clojure.core/inc 7]}
     {:refs [clojure.core/+ 1]}]
    {1 #{[clojure.core/inc 7]
         [clojure.core/+ 2]}
    2 #{[clojure.core/inc 7]
         [clojure.core/+ 1]}}
    [1 2]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(defn dataflow-analysis
  "Performs dataflow analysis using initialization, transfer and join functions on SSA form."
  [entry ssa init transfer join]
  (let [outs (init (g/nodes ssa))] ;outs is a map from block to out(block) value
    ; TODO should feed them in order from entry 
    (loop [worklist outs] ; worklist init'd to init state of blocks
      (let [n (peek worklist) ; pop off the worklist
            in (reduce
                 (fn [out predecesor]
                   (join out (get outs predecessor)))
                 {} (g/incoming ssa n))
            out (transfer n in)])
      (cond
        (not (= out (get outs n))) (do
                                     (conj outs {n out})
                                     (recur (cons (pop worklist) (g/neighbors ssa n))))
        (not (empty? worklist)) (recur (pop worklist))
        :else outs))))

#_(defn global-cse
  "Performs Global Common Subexpression Elimination optimization on CSE."
  [entry ssa init trasfer join]
  (dataflow-analysis entry ssa init transfer join))

#_(defn init-cse
  [blocks]
  (reduce
    (fn [outs block]
      (conj outs {block #{}}))
    {} blocks))

#_(defn transfer-cse
  [computed-exprs block]
  (->
    (reduce
      (fn [new-block {f :refs id :id :as instr}]
        (if (contains? computed-exprs f)
          (conj new-block {:id id :value (get computed-exprs f)})
          (conj new-block instr)))
      [] (:data block))) 
  (union computed-exprs))

#_(defn join-cse
  [out predecessor]
  (reduce
    (fn [outs {f :refs id :id :as expr}]
      (if (not (contains? out f))
        (conj outs expr)
        (let [ex-expr (get )])
        )
      )
    )
  )

#_(defn cse
  "Performs local common subexpression elimination on a basic block on pure functions."
  [block pure?]
  (second
    (reduce
      (fn [[computed-exprs new-block] {f :refs id :id :as instr}]
        (cond
          (contains? computed-exprs f)
          [computed-exprs
           (conj new-block {:id id :value (get computed-exprs f)})]
          (pure? f)
          [(assoc computed-exprs f id)
           (conj new-block instr)]  
          :else
          [computed-exprs (conj new-block instr)]))
      [{} []] block)))


(defmacro overlay [g & body]
  `(let [g# ~g]
     (reify
       g/Graph
       (nodes [g#] (nodes g#))
       (edges [g#] (edges g#))
       (has-node? [g# node] (contains? (g/nodes g#) node))
       (has-edge? [g# n1 n2] (contains? (g/edges g#) [n1 n2]))
       (neighbors [g#] (partial g/neighbors g#))
       (neighbors [g# node] (seq (g/nodes g#)))
       (degree [g# node] (count (g/neighbors g# node))))
     ~@body))


(comment
  ;; Uses ::value to mark the value passed along from previous block (one value, CPS style)
  ;; Uses special :set-id op for pseudo-phis. They are just the movs that would lead into the phi
  ;; Exceptions are the ::value passed into exception recovery blocks
  ;; :ref op is for fn invocation

  (clojure.pprint/pprint
    (second (parse-to-state-machine '[(if-not (zero? x)
                                         (bob (dec x) (inc y))
                                         y)])))

  (clojure.pprint/pprint
    (second (parse-to-state-machine '[(let [x (inc 7)] (+ 1 x 2)
                                        (+ 1 x 2))])))

  (clojure.pprint/pprint
    (-> 
      (parse-to-state-machine
        '[(let [x (inc 7)] (+ 1 x 2)
            (+ 1 x 2))])
      second
      :blocks
      (ssa->loom
        #(into #{} (keys %))
        #())))

  (clojure.pprint/pprint
    (->>
      (parse-to-state-machine
        '[(let [x (inc 7)] (+ 1 x 2)
            (+ 1 x 2))])
      second
      :blocks
      keys
      (into #{})))

  

  (clojure.pprint/pprint
    (reduce
      (fn [edges [block instrs]]
        (let [last-instr (last instrs)
              to (:block last-instr)]
          (if to
            (conj edges [block to])
            edges)))
      #{}
      (->>
        (parse-to-state-machine
          '[(loop [x 3]
              (try
                (cond
                  (= x 1) (recur 1)
                  (= x 3) (recur 2)
                  :else (recur 3))
                (catch RuntimeException x (.printStackTrace x))))])
        second
        :blocks)))

  (clojure.pprint/pprint
    (reduce
      (fn [nodes instr]
        (conj nodes instr))
      #{}
      (->
        (parse-to-state-machine
          '[(let [x (inc 7)] (+ 1 x 2)
              (+ 1 x 2))])
        second
        :blocks
        vals
        (#(apply concat %)))))

  (clojure.pprint/pprint
    (->>
      (parse-to-state-machine
        '[(let [x (inc 7)] (+ 1 x 2)
            (+ 1 x 2))])
      second
      :blocks
      vals
      (apply concat )
      (into #{})))

  (clojure.pprint/pprint
    (->
      (parse-to-state-machine
        '[(loop [x 3]
            (try
              (cond
                (= x 1) (recur 1)
                (= x 3) (recur 2)
                :else (recur 3))
              (catch RuntimeException x (.printStackTrace x))
              ))])
      second
      :blocks
      vals    
      flatten)) 

  (clojure.pprint/pprint
    (cse
      (->
        (parse-to-state-machine
          '[(let [x (inc 7)] (+ 1 x 2)
              (+ 1 x 2))])
        second
        :blocks
        vals
        first)
      identity))

  (clojure.pprint/pprint
    (second (parse-to-state-machine '[(fn []
                                        (map #(+ 1 %) l)
                                        )]
                                    {'foo 'foo} {})))

  (clojure.pprint/pprint
    (second (parse-to-state-machine '[(fn bob
                                        ([x]
                                         (bob 2 3))
                                        ([x y]
                                         (if-not (zero? x)
                                           (recur (dec x) (inc y))
                                           y)))])))

  (clojure.pprint/pprint
    (second (parse-to-state-machine '[(loop [x 3] (try
                                            (cond
                                            (= x 1) (recur 1)
                                            (= x 3) (recur 2)
                                            :else (recur 3))
                                            (catch RuntimeException x (.printStackTrace x))
                                            ))] ))))
