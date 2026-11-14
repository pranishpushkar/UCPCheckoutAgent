# Equivalent mutants (H3, ADR 0009)

An entry counts only with the owner's `approved:` line, which a session adds only after the owner has approved it in chat.
A mutant is matched by class, method, mutator and description, never by line, so a refactor does not silently invalidate an entry.
A mutant on a star path that is listed here is the only kind of survivor allowed there; "unreachable code" is a finding, not an equivalent.

Format, one block per mutant (there are no entries yet):

```
### <fully qualified class>.<method> <mutator name>
- description: <PIT's description of the mutation, for example "removed call to ...", "replaced return of int ...">
- why it is equivalent: <the observable behaviour does not change, and why>
- the test that shows it: <test class and method that passes with and without the mutation, or "none possible">
- approved: owner <date>
```
