# worldlet

**The stairs grow only while the moon sleeps.**

```clojure
{:id :stairs-dream
 :when [[:= :moon :asleep]]
 :add {:stairs 1}}
```

A tiny Clojure interpreter for imaginary worlds. Write facts and rules as data,
then step through the consequences.

With Java and the [Clojure CLI](https://clojure.org/guides/install_clojure) installed:

```sh
clojure -M:demo
```

```text
The stairs grow only while the moon sleeps.
hour  moon   stairs
0     awake  0
1     awake  0
2     awake  0
3     asleep 0
4     asleep 1
5     asleep 2
6     awake  3
7     awake  3
```

The complete world is [four rules in an EDN file](resources/worldlet/staircase.edn).
The moon falls asleep when the hour is 2 and wakes when it is 5. Rules see the
**beginning** of each step: the staircase still grows on the step when the moon
wakes. Nothing happens in a hidden order.

## Make a world

```clojure
(require '[worldlet.core :as w])

(def room
  (w/world {:door :closed :visits 0}
           [{:id :open-door
             :when []
             :set {:door :open}}
            {:id :enter
             :when [[:= :door :open]]
             :add {:visits 1}}]))

(def first-step (w/step room))
(:fired first-step)             ; => [:open-door]
(:facts (:world first-step))    ; => {:door :open, :visits 0}

(mapv #(get-in % [:world :facts :visits]) (w/advance room 3))
;; => [0 1 2]
```

`room` is unchanged. `step` returns a new world and the IDs of fired rules;
`advance` returns a vector of step results, excluding the initial world. A world
starts at `:tick 0`, and every step increments it, even if no rules fire.

## The whole language, for now

Facts are a flat map from keywords to integers, keywords, strings, booleans or
`nil`. All facts must exist when the world is created.

Each rule has a unique keyword `:id`, a `:when` vector, and one or both effects:

| Part | Meaning |
| --- | --- |
| `:when [[:= :moon :asleep]]` | All predicates must hold. `[]` always holds. |
| `:set {:moon :awake}` | Replace a fact with a literal value. |
| `:add {:stairs 1}` | Add an integer to an integer fact. Negative values subtract. |

Predicates are `[operator fact-key literal]`. Operators are `:=`, `:not=`, `:<`,
`:<=`, `:>` and `:>=`; ordered comparisons require integers. There are no variable
bindings, expressions or user-defined functions. Integer addition does not wrap
at machine-word boundaries.

### One snapshot, one transition

1. Check the world and all its rules, including inactive ones.
2. Decide which rules fire using the current facts.
3. Calculate their writes using those same facts.
4. Reject conflicting writes; otherwise return the next world.

Two active rules writing the same fact cause an `ExceptionInfo`, **even if they
would write the same value**. The error data identifies the fact and rule IDs.
There is no last-writer-wins priority. A rule also cannot both set and add to the
same fact. Effects that would invalidate a numeric predicate or addition on the
next step are rejected before a world is returned.

For invalid rules, exception data includes the original `:rule` and its
zero-based `:rule-index`. Duplicate-ID errors include `:rule-ids`, listing each
duplicated ID once in first appearance order.

Independent rules can be reordered without changing the resulting facts. The
`:fired` trace follows the order in the rules vector. Every matching rule fires
on every step; use a fact in its guard if it should fire only once.

Worlds are ordinary persistent Clojure values: keep one, step it, or start a
different continuation from it. The interpreter never evaluates code from a
rule. This is a data language, **not a resource-limited sandbox**: callers remain
responsible for input size and the number of steps retained in memory.

## Explore a different continuation

Keep a world from a history, then change a fact to try a counterfactual. No
special branching API is needed: each branch is an ordinary immutable value.

```clojure
(require '[worldlet.core :as w]
         '[worldlet.demo :as demo])

(def saved (:world (last (w/advance (demo/staircase) 3))))
;; saved is at tick 3: the moon has just fallen asleep, with zero stairs.
(def waking-early (assoc-in saved [:facts :moon] :awake))

(defn stairs-after [branch]
  (mapv #(get-in % [:world :facts :stairs]) (w/advance branch 2)))

(stairs-after saved)          ; => [1 2]
(stairs-after waking-early)   ; => [0 0]
(:tick waking-early)          ; => 3 (the branch keeps the saved tick)
(:facts saved)               ; => {:hour 3, :moon :asleep, :stairs 0}
```

Both continuations use the same rules. Changing one branch does not change the
saved world or the other branch. `step` and `advance` validate edited worlds too,
so a branch must still satisfy the language's fact and rule constraints. These
values are in-memory snapshots, not an automatic persistence or merge system.

## Test

```sh
clojure -M:test
```

The tests cover the example trace, snapshot timing, conflicting writes,
predicates, validation, independent continuations, empty worlds and integer
overflow boundaries.

This first version deliberately stops at a small interpreter. No renderer,
network service, randomness, scheduler or automatic persistence is included.
