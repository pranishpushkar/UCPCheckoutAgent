# Equivalent mutants (H3, ADR 0009)

An entry counts only with the owner's `approved:` line, which a session adds only after the owner has approved it in chat.
A mutant is matched by class, method, mutator and description, never by line, so a refactor does not silently invalidate an entry.
A mutant on a star path that is listed here is the only kind of survivor allowed there; "unreachable code" is a finding, not an equivalent.

Format, one block per mutant. **An entry without an `approved:` line is only a proposal and still counts as a survivor.** Proposals from H3 P5 follow the format block.

```
### <fully qualified class>.<method> <mutator name>
- description: <PIT's description of the mutation, for example "removed call to ...", "replaced return of int ...">
- why it is equivalent: <the observable behaviour does not change, and why>
- the test that shows it: <test class and method that passes with and without the mutation, or "none possible">
- approved: owner <date>
```

## Proposed in H3 P5 (agent-core: approval and json), not yet approved

### 1. dev.ucplab.core.approval.DefaultApprovalService.open RemoveConditionalMutator_EQUAL_IF
- description: removed conditional - replaced equality check with true (the test `old.status() == ApprovalStatus.PENDING` in the loop that replaces a pending approval)
- why it is equivalent: the call that follows, `approvals.transition(old.id(), PENDING, EXPIRED, ...)`, is a compare-and-set on the expected status PENDING: for an approval that is not PENDING it changes nothing and returns false. Calling it for every old approval therefore leaves every stored approval as it was
- the test that shows it: `DefaultApprovalServiceMutationTest.open_again_expiresThePendingApproval_andLeavesDecidedOnesAlone` (a CONSUMED approval stays CONSUMED, a PENDING one becomes EXPIRED) passes with and without the mutation; the opposite mutation (never replace) is killed by it

### 2. dev.ucplab.core.approval.DefaultApprovalService.pending RemoveConditionalMutator_EQUAL_ELSE
- description: removed conditional - replaced equality check with false (`intents.find(auth.user(), id).isEmpty()`: the owner check before the approval lookup never fires)
- why it is equivalent: the lookup that follows, `approvals.findLatest(auth.user(), id)`, only returns an approval whose owner is that user, and an approval's owner is always its intent's owner (I9, 18.1), so for an intent the user does not own it is empty as well. The first check is defence in depth with no outcome of its own
- the test that shows it: `DefaultApprovalServiceTest.decide_returnsNotFound_forAnotherUserWithTheToken` asserts `pending(bob, id)` is empty and passes with and without the mutation

### 3. dev.ucplab.core.approval.InMemoryApprovalRepository.transition BooleanFalseReturnValsMutator
- description: replaced boolean return with false (line `return false;` when the approval is missing or not in the expected status)
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical

### 4. dev.ucplab.core.approval.InMemoryApprovalRepository.transition BooleanTrueReturnValsMutator
- description: replaced boolean return with true (line `return true;` after the approval was moved)
- why it is equivalent: the line already returns the constant true: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical

### 5. dev.ucplab.core.json.CanonicalJson$Reader.array EmptyObjectReturnValsMutator
- description: replaced return value with Collections.emptyList (the `return frozen(list)` of an empty array)
- why it is equivalent: `frozen` returns `Collections.unmodifiableList` of an empty list; the mutation returns `Collections.emptyList()`. Both are empty, unmodifiable and equal; no caller can tell them apart
- the test that shows it: `JsonReaderMutationTest.whitespaceAndEmptyContainers_readExactly` (an empty array reads as `List.of()`) passes with and without the mutation

### 6. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of the exponent sign test)
- description: removed conditional - replaced equality check with true (one of the two comparisons in `charAt(pos) == '+' || charAt(pos) == '-'`, so a character after the exponent letter is always taken as its sign)
- why it is equivalent: taking one extra character after `e` or `E` only moves the read position; the number's text is cut from the document with `substring(start, pos)` and then parsed, and a character that is not a sign or a digit makes that parse fail with the same IllegalArgumentException ("bad number") as before. Signs and digits read the same
- the test that shows it: `JsonReaderMutationTest.lenientReader_readsDecimalsAndExponents` (1e+3, 1e-3, 1e, 1e+) passes with and without the mutation

### 7. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_ELSE (line of the exponent sign test)
- description: removed conditional - replaced equality check with false (the second comparison of the same sign test, so any character after the exponent letter that is not `+` is skipped)
- why it is equivalent: as the entry above: the extra character is skipped, then `substring` and the number parse decide, with the same IllegalArgumentException for anything that is not a number
- the test that shows it: the same test

### 8. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of the empty-or-minus test)
- description: removed conditional - replaced equality check with true (`digits.isEmpty() || digits.equals("-")`)
- why it is equivalent: the guard only chooses the message of the refusal ("unexpected character"). Without it an empty or `-` text reaches `Long.parseLong` and `BigDecimal`, fails there, and is refused with an IllegalArgumentException ("bad number" or "integer out of range"). The only difference is the exception's text, which only a script author sees (agent-runtime `Script.parse` passes it on); every network-facing caller maps any IllegalArgumentException to a fixed text (CheckoutParser: "unparseable body")
- the test that shows it: `JsonReaderMutationTest.integers_readAsLong_andOutOfRangeFollowsTheReader` (a lone `-` and `x` are refused) passes with and without the mutation

### 9. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_ELSE (line of the empty-or-minus test)
- description: removed conditional - replaced equality check with false (the same guard, the other comparison)
- why it is equivalent: as the entry above: only the exception text changes
- the test that shows it: the same test

### 10. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of `integral ? parseIntegral : BigDecimal`)
- description: removed conditional - replaced equality check with true (a decimal or an exponent is also sent to `parseIntegral`)
- why it is equivalent: `parseIntegral` of a text that is not an integer fails in `Long.parseLong` and then, for the lenient reader, returns `new BigDecimal(digits)`, the value the other branch returns; the strict reader never reaches this line with a decimal (it refuses it earlier). The result is the same number
- the test that shows it: `JsonReaderMutationTest.lenientReader_readsDecimalsAndExponents` passes with and without the mutation (the opposite mutation, always BigDecimal, is killed by `integers_readAsLong_...`)

### 11. dev.ucplab.core.json.CanonicalJson$Reader.object RemoveConditionalMutator_EQUAL_ELSE (the `peek() != '"'` key test)
- description: removed conditional - replaced equality check with false (a key that does not start with a quote is not refused here)
- why it is equivalent: the next call, `string()`, begins with `expect('"')`, which refuses the same input with an IllegalArgumentException. Only the text differs ("expected a string key" against "expected '\"'"), seen only by a script author as for the entries above
- the test that shows it: `JsonReaderMutationTest.missingPunctuation_isRefused` (`{1:2}`) passes with and without the mutation

### 12. dev.ucplab.core.json.CanonicalJson$Reader.string ConditionalsBoundaryMutator (the `pos + 4 > s.length()` test of a unicode escape)
- description: changed conditional boundary (`>` becomes `>=`)
- why it is equivalent: the two differ only when exactly four characters follow `\u` and the document then ends: the original reads the escape and refuses the missing closing quote ("unterminated string"), the mutation refuses the short escape ("short unicode escape"). Both are an IllegalArgumentException for a document that is malformed either way; only the text differs
- the test that shows it: `JsonReaderMutationTest.brokenStringsAndEscapes_areRefused` (`"\u0041` and `"\u12`) passes with and without the mutation

## Proposed in H3 P6 (agent-core: intent store, transitions, recovery), not yet approved

### 13. dev.ucplab.core.intent.InMemoryIntentRepository.lambda$inState$11 EmptyObjectReturnValsMutator
- description: replaced return value with "" (the id tie-break of the sort in `inState`, so intents created at one instant are not ordered by id)
- why it is equivalent: `inState` has one consumer, `InMemoryRecoveryScan.approvalsDue`, which sorts what it gets by (expiry, id), a total order, before it limits and returns. The order `inState` returns is never observed
- the test that shows it: `RecoveryScanContract` (approvals due, oldest expiry first, at most the limit) passes with and without the mutation

### 14. dev.ucplab.core.intent.InMemoryIntentRepository.renewLease BooleanFalseReturnValsMutator
- description: replaced boolean return with false (line `return false;` when the intent is missing or held by another owner)
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical

### 15. dev.ucplab.core.intent.InMemoryIntentRepository.renewLease BooleanTrueReturnValsMutator
- description: replaced boolean return with true (line `return true;` after the lease was extended)
- why it is equivalent: the line already returns the constant true: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical

### 16. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanFalseReturnValsMutator (the `return false;` inside the refusal `if`)
- description: replaced boolean return with false
- why it is equivalent: the line already returns the constant false
- the test that shows it: none possible: the bytecode result is identical

### 17. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanTrueReturnValsMutator (the `return true;` after the driver was started)
- description: replaced boolean return with true
- why it is equivalent: the line already returns the constant true
- the test that shows it: none possible: the bytecode result is identical

### 18. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanFalseReturnValsMutator (the `return false;` in the `catch (RuntimeException)`)
- description: replaced boolean return with false
- why it is equivalent: the line already returns the constant false
- the test that shows it: none possible: the bytecode result is identical

### 19. dev.ucplab.core.recovery.RecoveryWorker.lambda$forgetDueUnclaimed$2 RemoveConditionalMutator_EQUAL_IF
- description: removed conditional - replaced equality check with true (the `!claimed.contains(e.getKey())` operand of the removal test, so due entries are forgotten whether or not the claim returned them)
- why it is equivalent: an entry that is due and that the claim returned is removed by `runOnce` itself, in the loop that starts the drivers, before the in-flight check and before any driver starts (`notBefore.remove(id, notYet)`). Forgetting it a few lines earlier leaves the same map. Checked by removing the operand outright: every `Recovery*` test, including the new ones, stays green
- the test that shows it: `RecoveryWorkerMutationTest.aParkedIntent_isLeftAlone_untilItIsDue` and `RecoveryWorkerParkingTest` pass with and without the mutation (the other operand, `!now.isBefore(...)`, is killed by the first)

### 20. dev.ucplab.core.recovery.RecoveryLoop.pass RemoveConditionalMutator_EQUAL_IF (the `closed.get()` of the first test)
- description: removed conditional - replaced equality check with true (a closed loop does not return at the first check)
- why it is equivalent: `pass` tests the closed flag twice: before it takes the permit and again after. A pass that gets past the first check on a closed loop returns at the second, still releasing the permit it took; no pass ever runs. The two checks only differ for a `close()` that lands between them, which one thread cannot arrange
- the test that shows it: `RecoveryLoopMutationTest.aClosedLoop_schedulesNothing_andRunsNoPass` and `RecoveryLoopCloseTest.close_noPassStartsAfterwards` pass with and without the mutation

### 21. dev.ucplab.core.recovery.RecoveryLoop.pass RemoveConditionalMutator_EQUAL_ELSE (the `closed.get()` of the second test)
- description: removed conditional - replaced equality check with false (the re-check after the permit is taken never returns)
- why it is equivalent: the first check, before the permit, already returns for a closed loop; the re-check only matters for a `close()` that lands between the two. A pass that started in that window would run once more; the loop is closed for every pass after it. No single-threaded test can place a `close()` inside two adjacent statements, and a stress test of it would not be deterministic
- the test that shows it: the same tests: both pass with and without the mutation

### 22. dev.ucplab.core.recovery.RecoveryLoop.pass VoidMethodCallMutator (the `passThread.set(null)` in `finally`)
- description: removed call to java/util/concurrent/atomic/AtomicReference::set
- why it is equivalent: `passThread` is read only by `close(Duration)`, and only after the permit could not be taken, that is while a pass is running, which has set its own thread. A stale thread left by a finished pass is overwritten by the next pass before it is read; if none is running, `close` takes the permit and returns before reading it
- the test that shows it: `RecoveryLoopCloseTest` (a running pass is interrupted, an idle loop closes clean) passes with and without the mutation
