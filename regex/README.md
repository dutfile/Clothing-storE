# TRegex

TRegex is a generic regular expression engine that uses the GraalVM compiler and Truffle API to execute regular expressions in an efficient way.
Its role is to provide support for Truffle languages that need to expose regular expression functionality.
In its current iteration, TRegex provides an implementation of ECMAScript regular expressions (ECMAScript regular expressions are based on the widely popular Perl 5 regular expressions) and a subset of Python regular expressions.
A distinguishing feature of TRegex is that it compiles regular expressions into finite-state automata.
This means that the performance of searching for a match is predictable (linear to the size of the input).


## Overview

Unlike most regular expression engines which use backtracking, TRegex uses an automaton-based approach.
The regex is parsed and then translated into a nondeterministic finite-state automaton (NFA).
A powerset construction is then used to expand the NFA into a deterministic finite-state automaton (DFA).
The resulting DFA is then executed when matching against input strings.
At that point, TRegex exploits the GraalVM compiler and Truffle to get efficient machine code when interpreting the DFA.

The benefit of using this approach is that finding out whether a match is found can be done during a single pass over the input string: whenever several alternative ways to match the remaining input are admissible, TRegex considers all of them simultaneously.
This is in contrast to backtracking approaches which consider all possible alternatives separately, one after the other.
This can lead to up to exponential execution times in specific adversarial cases (https://swtch.com/~rsc/regexp/regexp1.html).
Since TRegex adopts the automaton-based approach, the runtime of the matching procedure is consistent and predictable.
However, the downside of the automaton-based approach is that it cannot cover some of the features which are now commonly supported by regu