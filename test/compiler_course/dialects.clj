(ns compiler-course.dialects
  (:require [matches.nanopass.dialect :as d
             :refer [=> ==> ===> def-dialect def-derived unparse-dialect
                     validate valid? show-parse]]
            [matches.types :refer [ok]]
            [matches :refer [matcher]]))

(def cmp? #{'eq? '< '<= '> '>=})

(def r1-keyword? (into cmp? '[define let if and or not void read + -
                              vector vector-length vector-ref vector-set]))

(def-dialect R1
  (terminals [i int?]
             [v symbol?]
             [b boolean?]
             [cmp `cmp?])
  (Type [type :enforce]
        (?:letrec [simple   (| Integer Boolean Void)
                   compound (Vector (?:* $type))
                   function ((?:* $type) -> $type)
                   type     (| $simple $compound $function)]
          $type))
  (Exp [e]
       ?i ?v ?b
       (read)
       (- ?e) (+ ?e0 ?e1) (- ?e0 ?e1)
       (and ?e0 ?e1) (or ?e0 ?e1) (not ?e)
       (?cmp ?e0 ?e1)
       (let ([?v ?e]) ?e:body)
       (if ?e ?e:then ?e:else)
       (vector ??e*) (vector-length ?e)
       (vector-ref ?e ?i) (vector-set! ?e0 ?i ?e1)
       (void)
       (& (?e:f ??e:args) (? _ seq?))) ;; FIXME: this list should not unify with a vector... but it does if I don't add the (? _ seq?) rule.
  (Define [define]
    (define (?v:name (?:* [?v* ?type*])) ?type ?e))
  (Program [p]
           ;; Programs have mulitple top-level forms, so just contain them in a vector
           ?e
           [??define* ?e])
  (entry Program))

(def-derived R1Fun R1
  (Program [p]
           - [??define ?e]
           - ?e
           + (program ??define*)))

(def-derived RFunRef R1Fun
  (Exp [e]
       - (& (?e:f ??e:args) (? _ seq?))
       + (call (funref ?e:f) ??e:args)
       + (funref ?v)))

(def-derived Shrunk RFunRef
  (terminals - [cmp `cmp?])
  (Exp [e]
       - (- ?e0 ?e1)
       - (?cmp ?e0 ?e1)
       + (< ?e0 ?e1)
       + (eq? ?e0 ?e1)
       - (and ?e0 ?e1)
       - (or ?e0 ?e1)))

(def-derived Typed Shrunk ;; dead end dialect :)
  (Exp [e {:compiler-course.r1/type ?type}]))

(def-derived Alloc Shrunk
  (terminals + [name symbol?])
  (Exp [e]
       - (vector ??e*)
       + (collect ?i)
       + (allocate ?i ?type)
       + (global-value ?name)))

;; FIXME: for some reason setting ?atm to be enforced causes downstream tests to fail. Not sure why...
;; - It doesn't change the output of the simplified pass
;; - the failing data seems valid, but the data doesn't validate.
;; - perhaps this is a bug within the checker?
(def-derived Simplified Alloc
  (Atom [atm #_:enforce]
        (read)
        ?i ?v ?b)
  - Exp
  (NotExp [ne]
          ?e
          (not ?ne))
  (Exp [e]
       ?atm
       (- ?atm)
       (+ ?atm0 ?atm1)
       (< ?atm0 ?atm1)
       (eq? ?atm0 ?atm1)
       (not ?atm)
       (let ([?v ?e]) ?e:body)
       (if ?ne ?e:then ?e:else)
       (vector-ref ?e ?i) (vector-set! ?e0 ?i ?e1)
       (void)
       (collect ?i)
       (allocate ?i ?type)
       (global-value ?name)))

;; The book's C... language:
(def-derived Explicit Simplified
  (terminals + [lbl symbol?])
  - Exp
  - NotExp
  (Pred [pred :enforce]
        ?b
        ?v
        (not ?pred)
        (< ?atm0 ?atm1)
        (eq? ?atm0 ?atm1)
        (vector-ref ?v ?i)
        (if ?pred (goto ?lbl:then) (goto ?lbl:else)))
  (Atom [atm :enforce]
        + (void)
        + (global-value ?name))
  (Exp [e]
       ?atm
       ?pred
       (+ ?atm0 ?atm1) (- ?atm)
       (vector-ref ?v ?i)
       (vector-set! ?v ?i ?atm)
       (collect ?i)
       (allocate ?i ?type)
       (funref ?lbl)
       (call ?atm ??atm*))
  (Stmt [stmt]
        (assign ?v ?e))
  (Tail [tail]
        (if ?pred (goto ?lbl:then) (goto ?lbl:else))
        (tailcall ?atm ??atm*)
        (return ?e))
  (Block [block]
         (block ?lbl [??v*] ??stmt* ?tail))
  (Define [d]
    (define ?lbl [?v* ?type*] ... ?type
      ;; the book specifies an undefined ?info here, too...
      (?:+map ?lbl* ?block*)))
  (Program [program]
           (program [??v*] (?:+map ?lbl* ?block*))))

(def-derived Uncovered Explicit
  - Program
  (Program [program]
           (program (?:*map ?v ?type) (?:+map ?lbl* ?block*))))

(def jmp-cond #{true '< 'eq?})

(def-dialect Selected
  (terminals [lbl symbol?]
             [v symbol?]
             [i int?]
             [jc `jmp-cond])
  (Type [type :enforce] Integer Boolean (Vector ??type) Void)
  (ByteReg [bytereg :enforce] (byte-reg (| ah al bh bl ch cl dh dl)))
  (Reg [reg]
       (reg (| rax r11 r15 rsi rdi)))
  (Arg [arg]
       ?reg
       (int ?i)
       (deref ?i:offset ?reg)
       (deref ?i:scale ?i:offset ?reg)
       (v ?v)
       (global-value ?lbl))
  ;; this could get fancy and encode some of the restrictions
  (Stmt [stmt]
        (callq ?lbl)
        (cmpq ?arg0 ?arg1)
        (movq ?arg0 ?arg1)
        (addq ?arg0 ?arg1)     ; (+ 1 2)
        (negq ?arg)            ; (- 1)
        (xorgq (int 1) ?arg)   ; (not 1)
        (set ?jc ?bytereg)     ; get cmp flag 1
        (movzbq ?bytereg ?arg) ; get cmp flag 2
        (jump ?jc ?lbl)
        (indirect-callq ?arg (int ?i))
        (tailjmp ?arg (int ?i))
        (leaq ?arg (& ?reg (reg ?_))))
  ;; there is specific valid sequencing like cmp -> jump -> jump, but I'm not
  ;; sure how or if I can encode that here...
  ;; TODO: attach additional validation rules to forms? Some could have rules
  ;; like above attached but they don't fit at the expression level.
  (Tail [tail]
        (jump ?jc ?lbl)
        (retq))
  (Block [block]
         (block ?lbl [??v*] ??stmt* ?tail))
  (Define [define]
    (define ?lbl ?type (?:*map ?lbl* ?block*)))
  (Program [program]
           (program (?:*map ?v ?type) (?:+map ?lbl* ?define*)))
  (entry Program))


(def-derived RegAllocated Selected
  (Caller [caller :enforce] (reg (| rax rcx rdx rsi rdi r8 r9 r10 r11)))
  (Callee [callee :enforce] (reg (| rsp rbp rbx r12 r13 r14 r15)))
  - Reg
  (Reg [reg] ?caller ?callee)
  (Loc [loc]
       ?caller
       ?callee
       (stack ?i)
       (heap ?i))
  (Arg [arg]
       - ?reg
       + ?loc)
  - Program
  (Program [program]
           (program (?:*map ?v ?type) (?:*map ?v* ?loc*)
                    [??block*])))

(def-derived RemoveUnallocated RegAllocated
  (Arg [arg]
       - (v ?v))
  (Program [program]))

(def-derived Patched RemoveUnallocated
  (Stmt [stmt]
        - (movq ?arg0 ?arg1)
        + (movq ?arg0 (& ?arg1 (? arg1 not= ?arg0)))
        - (addq ?arg0 ?arg1)
        + (addq (& ?arg0 (? arg0 not= (int 0))) ?arg1))
  ;; Ensure Program is still the entrypoint
  (Program [program]))


(def-derived Patched+ Patched
  - Program
  (SaveReg [savereg]
           (movq ?callee (stack* ?i)))
  (Program [program]
           (program (?:*map ?v ?type)
                    (?:*map ?v* ?loc*)
                    [??savereg*]
                    [??block*])))
