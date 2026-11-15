# Equivalent mutants (H3, ADR 0009)

An entry counts only with the owner's `approved:` line, which a session adds only after the owner has approved it in chat.
A mutant is matched by class, method, mutator and description, never by line, so a refactor does not silently invalidate an entry.
A mutant on a star path that is listed here is the only kind of survivor allowed there; "unreachable code" is a finding, not an equivalent.

Format, one block per mutant. **An entry without an `approved:` line is only a proposal and still counts as a survivor.** The owner's decisions of 2026-11-15 on entries 1 to 22 are recorded below (entry 2 was refused and killed by a test, so it is gone; the numbers of the others are kept). Entry 21 is "justified", not equivalent. Entries 66 and 67 (P8) were refused and are gone: `CircuitBreaker.tryAcquire` is an exhaustive switch with no `default`. Entries 63 to 65 (the constant returns of the old switch) went with it: the rewritten switch has no such mutant (PIT, run `ucpE`). Entries 24 and 30 (P7) were refused and killed by tests, so they are gone too. Entries 8, 9, 11 and 12 are about the text of an IllegalArgumentException, which is deliberately not asserted.

```
### <fully qualified class>.<method> <mutator name>
- description: <PIT's description of the mutation, for example "removed call to ...", "replaced return of int ...">
- why it is equivalent: <the observable behaviour does not change, and why>
- the test that shows it: <test class and method that passes with and without the mutation, or "none possible">
- approved: owner <date>
```

## From H3 P5 (agent-core: approval and json)

### 1. dev.ucplab.core.approval.DefaultApprovalService.open RemoveConditionalMutator_EQUAL_IF
- description: removed conditional - replaced equality check with true (the test `old.status() == ApprovalStatus.PENDING` in the loop that replaces a pending approval)
- why it is equivalent: the call that follows, `approvals.transition(old.id(), PENDING, EXPIRED, ...)`, is a compare-and-set on the expected status PENDING: for an approval that is not PENDING it changes nothing and returns false. Calling it for every old approval therefore leaves every stored approval as it was
- the test that shows it: `DefaultApprovalServiceMutationTest.open_again_expiresThePendingApproval_andLeavesDecidedOnesAlone` (a CONSUMED approval stays CONSUMED, a PENDING one becomes EXPIRED) passes with and without the mutation; the opposite mutation (never replace) is killed by it
- approved: owner 2026-11-15

### 3. dev.ucplab.core.approval.InMemoryApprovalRepository.transition BooleanFalseReturnValsMutator
- description: replaced boolean return with false (line `return false;` when the approval is missing or not in the expected status)
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 4. dev.ucplab.core.approval.InMemoryApprovalRepository.transition BooleanTrueReturnValsMutator
- description: replaced boolean return with true (line `return true;` after the approval was moved)
- why it is equivalent: the line already returns the constant true: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 5. dev.ucplab.core.json.CanonicalJson$Reader.array EmptyObjectReturnValsMutator
- description: replaced return value with Collections.emptyList (the `return frozen(list)` of an empty array)
- why it is equivalent: `frozen` returns `Collections.unmodifiableList` of an empty list; the mutation returns `Collections.emptyList()`. Both are empty, unmodifiable and equal; no caller can tell them apart
- the test that shows it: `JsonReaderMutationTest.whitespaceAndEmptyContainers_readExactly` (an empty array reads as `List.of()`) passes with and without the mutation
- approved: owner 2026-11-15

### 6. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of the exponent sign test)
- description: removed conditional - replaced equality check with true (one of the two comparisons in `charAt(pos) == '+' || charAt(pos) == '-'`, so a character after the exponent letter is always taken as its sign)
- why it is equivalent: taking one extra character after `e` or `E` only moves the read position; the number's text is cut from the document with `substring(start, pos)` and then parsed, and a character that is not a sign or a digit makes that parse fail with the same IllegalArgumentException ("bad number") as before. Signs and digits read the same
- the test that shows it: `JsonReaderMutationTest.lenientReader_readsDecimalsAndExponents` (1e+3, 1e-3, 1e, 1e+) passes with and without the mutation
- approved: owner 2026-11-15

### 7. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_ELSE (line of the exponent sign test)
- description: removed conditional - replaced equality check with false (the second comparison of the same sign test, so any character after the exponent letter that is not `+` is skipped)
- why it is equivalent: as the entry above: the extra character is skipped, then `substring` and the number parse decide, with the same IllegalArgumentException for anything that is not a number
- the test that shows it: the same test
- approved: owner 2026-11-15

### 8. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of the empty-or-minus test)
- description: removed conditional - replaced equality check with true (`digits.isEmpty() || digits.equals("-")`)
- why it is equivalent: the guard only chooses the message of the refusal ("unexpected character"). Without it an empty or `-` text reaches `Long.parseLong` and `BigDecimal`, fails there, and is refused with an IllegalArgumentException ("bad number" or "integer out of range"). The only difference is the exception's text, which only a script author sees (agent-runtime `Script.parse` passes it on); every network-facing caller maps any IllegalArgumentException to a fixed text (CheckoutParser: "unparseable body")
- the test that shows it: `JsonReaderMutationTest.integers_readAsLong_andOutOfRangeFollowsTheReader` (a lone `-` and `x` are refused) passes with and without the mutation
- approved: owner 2026-11-15

### 9. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_ELSE (line of the empty-or-minus test)
- description: removed conditional - replaced equality check with false (the same guard, the other comparison)
- why it is equivalent: as the entry above: only the exception text changes
- the test that shows it: the same test
- approved: owner 2026-11-15

### 10. dev.ucplab.core.json.CanonicalJson$Reader.number RemoveConditionalMutator_EQUAL_IF (line of `integral ? parseIntegral : BigDecimal`)
- description: removed conditional - replaced equality check with true (a decimal or an exponent is also sent to `parseIntegral`)
- why it is equivalent: `parseIntegral` of a text that is not an integer fails in `Long.parseLong` and then, for the lenient reader, returns `new BigDecimal(digits)`, the value the other branch returns; the strict reader never reaches this line with a decimal (it refuses it earlier). The result is the same number
- the test that shows it: `JsonReaderMutationTest.lenientReader_readsDecimalsAndExponents` passes with and without the mutation (the opposite mutation, always BigDecimal, is killed by `integers_readAsLong_...`)
- approved: owner 2026-11-15

### 11. dev.ucplab.core.json.CanonicalJson$Reader.object RemoveConditionalMutator_EQUAL_ELSE (the `peek() != '"'` key test)
- description: removed conditional - replaced equality check with false (a key that does not start with a quote is not refused here)
- why it is equivalent: the next call, `string()`, begins with `expect('"')`, which refuses the same input with an IllegalArgumentException. Only the text differs ("expected a string key" against "expected '\"'"), seen only by a script author as for the entries above
- the test that shows it: `JsonReaderMutationTest.missingPunctuation_isRefused` (`{1:2}`) passes with and without the mutation
- approved: owner 2026-11-15

### 12. dev.ucplab.core.json.CanonicalJson$Reader.string ConditionalsBoundaryMutator (the `pos + 4 > s.length()` test of a unicode escape)
- description: changed conditional boundary (`>` becomes `>=`)
- why it is equivalent: the two differ only when exactly four characters follow `\u` and the document then ends: the original reads the escape and refuses the missing closing quote ("unterminated string"), the mutation refuses the short escape ("short unicode escape"). Both are an IllegalArgumentException for a document that is malformed either way; only the text differs
- the test that shows it: `JsonReaderMutationTest.brokenStringsAndEscapes_areRefused` (`"\u0041` and `"\u12`) passes with and without the mutation

## From H3 P6 (agent-core: intent store, transitions, recovery)
- approved: owner 2026-11-15

### 13. dev.ucplab.core.intent.InMemoryIntentRepository.lambda$inState$11 EmptyObjectReturnValsMutator
- description: replaced return value with "" (the id tie-break of the sort in `inState`, so intents created at one instant are not ordered by id)
- why it is equivalent: `inState` has one consumer, `InMemoryRecoveryScan.approvalsDue`, which sorts what it gets by (expiry, id), a total order, before it limits and returns. The order `inState` returns is never observed
- the test that shows it: `RecoveryScanContract` (approvals due, oldest expiry first, at most the limit) passes with and without the mutation
- approved: owner 2026-11-15

### 14. dev.ucplab.core.intent.InMemoryIntentRepository.renewLease BooleanFalseReturnValsMutator
- description: replaced boolean return with false (line `return false;` when the intent is missing or held by another owner)
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 15. dev.ucplab.core.intent.InMemoryIntentRepository.renewLease BooleanTrueReturnValsMutator
- description: replaced boolean return with true (line `return true;` after the lease was extended)
- why it is equivalent: the line already returns the constant true: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 16. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanFalseReturnValsMutator (the `return false;` inside the refusal `if`)
- description: replaced boolean return with false
- why it is equivalent: the line already returns the constant false
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 17. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanTrueReturnValsMutator (the `return true;` after the driver was started)
- description: replaced boolean return with true
- why it is equivalent: the line already returns the constant true
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 18. dev.ucplab.core.recovery.RecoveryWorker.driveNow BooleanFalseReturnValsMutator (the `return false;` in the `catch (RuntimeException)`)
- description: replaced boolean return with false
- why it is equivalent: the line already returns the constant false
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 19. dev.ucplab.core.recovery.RecoveryWorker.lambda$forgetDueUnclaimed$2 RemoveConditionalMutator_EQUAL_IF
- description: removed conditional - replaced equality check with true (the `!claimed.contains(e.getKey())` operand of the removal test, so due entries are forgotten whether or not the claim returned them)
- why it is equivalent: an entry that is due and that the claim returned is removed by `runOnce` itself, in the loop that starts the drivers, before the in-flight check and before any driver starts (`notBefore.remove(id, notYet)`). Forgetting it a few lines earlier leaves the same map. Checked by removing the operand outright: every `Recovery*` test, including the new ones, stays green
- the test that shows it: `RecoveryWorkerMutationTest.aParkedIntent_isLeftAlone_untilItIsDue` and `RecoveryWorkerParkingTest` pass with and without the mutation (the other operand, `!now.isBefore(...)`, is killed by the first)
- approved: owner 2026-11-15

### 20. dev.ucplab.core.recovery.RecoveryLoop.pass RemoveConditionalMutator_EQUAL_IF (the `closed.get()` of the first test)
- description: removed conditional - replaced equality check with true (a closed loop does not return at the first check)
- why it is equivalent: `pass` tests the closed flag twice: before it takes the permit and again after. A pass that gets past the first check on a closed loop returns at the second, still releasing the permit it took; no pass ever runs. The two checks only differ for a `close()` that lands between them, which one thread cannot arrange
- the test that shows it: `RecoveryLoopMutationTest.aClosedLoop_schedulesNothing_andRunsNoPass` and `RecoveryLoopCloseTest.close_noPassStartsAfterwards` pass with and without the mutation
- approved: owner 2026-11-15

### 21. dev.ucplab.core.recovery.RecoveryLoop.pass RemoveConditionalMutator_EQUAL_ELSE (the `closed.get()` of the second test)
- description: removed conditional - replaced equality check with false (the re-check after the permit is taken never returns)
- why it is justified: an untestable race. It is not equivalent: a pass can start just after `close()`, and `close()` still waits for that pass or interrupts it. The window lies between two adjacent statements, and no deterministic test can reach it without a test hook in production code.
- the test that shows it: the same tests: both pass with and without the mutation
- approved: owner 2026-11-15

### 22. dev.ucplab.core.recovery.RecoveryLoop.pass VoidMethodCallMutator (the `passThread.set(null)` in `finally`)
- description: removed call to java/util/concurrent/atomic/AtomicReference::set
- why it is equivalent: `passThread` is read only by `close(Duration)`, and only after the permit could not be taken, that is while a pass is running, which has set its own thread. A stale thread left by a finished pass is overwritten by the next pass before it is read; if none is running, `close` takes the permit and returns before reading it
- the test that shows it: `RecoveryLoopCloseTest` (a running pass is interrupted, an idle loop closes clean) passes with and without the mutation
- approved: owner 2026-11-15

## From H3 P7 (agent-core: `DefaultOrchestrator`)

### 23. dev.ucplab.core.orchestrator.DefaultOrchestrator.propose RemoveConditionalMutator_EQUAL_ELSE (the `lines.isEmpty()` refusal)
- description: removed conditional - replaced equality check with false (a proposal with no line is no longer refused here)
- why it is equivalent: `RuleBasedPolicyEngine.evaluateProposal`, called next, refuses an empty line list with the same exception type and the very same message ("a proposal needs at least one line"); nothing has been written by then. Checked by hand: with the guard turned off the new test, which asserts that message, stays green
- the test that shows it: `OrchestratorSecondRoundMutationTest.aProposalWithNoLine_isRefusedWithTheProposalsOwnMessage` passes with and without the mutation
- approved: owner 2026-11-15

### 25. dev.ucplab.core.orchestrator.DefaultOrchestrator.propose ConditionalsBoundaryMutator (the rationale cut, `length > 300`)
- description: changed conditional boundary (`>` becomes `>=`)
- why it is equivalent: at exactly 300 characters the cut `substring(0, 300)` returns the same 300 characters
- the test that shows it: `OrchestratorQuoteMutationTest.aProposal_isRecorded_withItsLinesItsRationaleAndItsDecision` (a rationale of 300 and one of 301) passes with and without the mutation
- approved: owner 2026-11-15

### 26. dev.ucplab.core.orchestrator.DefaultOrchestrator.propose RemoveConditionalMutator_EQUAL_ELSE (the `!decision.allowed()` early return)
- description: removed conditional - replaced equality check with false (a denied proposal goes on to a step)
- why it is equivalent: the intent is already REJECTED with no owed cancel; the step that follows only claims it again (this worker holds the lease) and reports idle, and the result is the same REJECTED proposal with no quote. No merchant call is made either way
- the test that shows it: `OrchestratorQuoteMutationTest.aDeniedProposal_isRejectedWithNoQuoteAndNoCall_andAnAllowedOneCarriesItsQuote` passes with and without the mutation
- approved: owner 2026-11-15

### 27. dev.ucplab.core.orchestrator.DefaultOrchestrator.propose RemoveConditionalMutator_EQUAL_IF (the `last.after() == AWAITING_APPROVAL` choice of quote)
- description: removed conditional - replaced equality check with true (the step's quote is always returned)
- why it is equivalent: a `Step` carries a quote only from `quoteReady`, which is the step that ends in AWAITING_APPROVAL; every other step's quote is already empty
- the test that shows it: the same test
- approved: owner 2026-11-15

### 28. dev.ucplab.core.orchestrator.DefaultOrchestrator.nextAttemptAt ConditionalsBoundaryMutator (`elapsed < pollInitial`)
- description: changed conditional boundary
- why it is equivalent: at equality the two branches give the same value: the initial delay and the elapsed time are equal
- the test that shows it: `OrchestratorQuoteMutationTest.thePollDelay_growsWithTheTimeSinceTheFirstUncertainty_isCapped_andStopsAtTheDeadline` passes with and without the mutation
- approved: owner 2026-11-15

### 29. dev.ucplab.core.orchestrator.DefaultOrchestrator.nextAttemptAt ConditionalsBoundaryMutator (`elapsed > pollMax`)
- description: changed conditional boundary
- why it is equivalent: at equality the cap and the elapsed time are equal
- the test that shows it: the same test
- approved: owner 2026-11-15

### 31. dev.ucplab.core.orchestrator.DefaultOrchestrator.quote RemoveConditionalMutator_EQUAL_ELSE (`create ? 0 : updates`)
- description: removed conditional - replaced equality check with false (the revision of a create is the number of updates)
- why it is equivalent: a create is sent only when the intent has no re-quote and no attempt in its cycle, so the number of updates it has issued is 0
- the test that shows it: `OrchestratorQuoteMutationTest.eachRequote_isAnUpdateUnderItsOwnNumberedKey` passes with and without the mutation
- approved: owner 2026-11-15

### 32. dev.ucplab.core.orchestrator.DefaultOrchestrator.quote RemoveConditionalMutator_EQUAL_IF (`create &&` in `orphanPossible`)
- description: removed conditional - replaced equality check with true
- why it is equivalent: a re-quote (not a create) implies a session and a complete attempt, so no create attempt can be unresolved and still unknown to the intent; `orphanPossible` is false either way
- the test that shows it: `OrchestratorQuoteMutationTest.aQuoteCycleThatAlreadyCalled_isExhausted_andOwesACancelOnlyForAPossibleOrphan` passes with and without the mutation
- approved: owner 2026-11-15

### 33. dev.ucplab.core.orchestrator.DefaultOrchestrator.quote RemoveConditionalMutator_EQUAL_IF (`intent.sessionId().isEmpty()` in the owed cancel)
- description: removed conditional - replaced equality check with true
- why it is equivalent: when the session is known the move itself already sets cancel_pending in the same write (table 7.2, retries exhausted), so forcing the flag changes nothing
- the test that shows it: the same test
- approved: owner 2026-11-15

### 34. dev.ucplab.core.orchestrator.DefaultOrchestrator.lambda$quoteCycle$13 BooleanTrueReturnValsMutator (the `op == CREATE || op == UPDATE` filter)
- description: replaced boolean return with true
- why it is equivalent: justified: no reachable state (a defensive filter). After the last complete, a quote cycle holds only creates and updates; any other attempt kind appears only once the intent is terminal
- the test that shows it: none possible: no attempt of another kind exists in a quote cycle
- approved: owner 2026-11-15

### 35. dev.ucplab.core.orchestrator.DefaultOrchestrator.lambda$quoteCycle$13 RemoveConditionalMutator_EQUAL_ELSE (the same filter)
- description: removed conditional - replaced equality check with false
- why it is equivalent: justified: no reachable state (a defensive filter). After the last complete, a quote cycle holds only creates and updates; any other attempt kind appears only once the intent is terminal
- the test that shows it: none possible
- approved: owner 2026-11-15

### 36. dev.ucplab.core.orchestrator.DefaultOrchestrator.lambda$quoteCycle$13 RemoveConditionalMutator_EQUAL_IF (the same filter)
- description: removed conditional - replaced equality check with true
- why it is equivalent: justified: no reachable state (a defensive filter). After the last complete, a quote cycle holds only creates and updates; any other attempt kind appears only once the intent is terminal
- the test that shows it: none possible
- approved: owner 2026-11-15

### 37. dev.ucplab.core.orchestrator.DefaultOrchestrator.pendingSelection RemoveConditionalMutator_EQUAL_ELSE (`last.status() != RESPONDED || responseJson().isEmpty()`)
- description: removed conditional - replaced equality check with false
- why it is equivalent: the two operands cover each other: an attempt that is not RESPONDED has no stored answer of the form that follows (a SENT one has none, an UNCERTAIN one stores a description with no `status`), so the next check returns empty either way
- the test that shows it: `OrchestratorQuoteMutationTest.aSelectionUpdateLeftSent_isNotRepeated` and `OrchestratorSecondRoundMutationTest.aStoredAnswerThatIsNotASelectionRequest_isNeverAnsweredWithAnUpdate` pass with and without the mutation
- approved: owner 2026-11-15

### 38. dev.ucplab.core.orchestrator.DefaultOrchestrator.pendingSelection RemoveConditionalMutator_EQUAL_IF (the same test, the other operand)
- description: removed conditional - replaced equality check with true
- why it is equivalent: as the entry above
- the test that shows it: the same tests
- approved: owner 2026-11-15

### 39. dev.ucplab.core.orchestrator.DefaultOrchestrator.lambda$selectionPending$17 EmptyObjectReturnValsMutator (the line ids)
- description: replaced return value with Stream.empty
- why it is equivalent: `selectionPending` only asks whether a selection is left; the line ids it gathers are used to build a selection that this method discards. Only `isPresent()` of the result is read
- the test that shows it: none possible
- approved: owner 2026-11-15

### 40. dev.ucplab.core.orchestrator.DefaultOrchestrator.onQuoteOutcome RemoveConditionalMutator_EQUAL_IF (`create ? written.withSessionId(view.id()) : written`)
- description: removed conditional - replaced equality check with true
- why it is equivalent: for an update the answer's session id has just been checked equal to the intent's own (I4), so setting it again changes nothing
- the test that shows it: `OrchestratorQuoteMutationTest.anUpdateAnsweredForAnotherSession_isRefused` passes with and without the mutation
- approved: owner 2026-11-15

### 41. dev.ucplab.core.orchestrator.DefaultOrchestrator.onQuoteOutcome RemoveConditionalMutator_EQUAL_IF (`create &&` of the Uncertain case)
- description: removed conditional - replaced equality check with true
- why it is equivalent: for an update the session is known, so `written.sessionId().isEmpty()` is false and the second operand decides the same way
- the test that shows it: `OrchestratorQuoteMutationTest.aNotSentOrLostCreate_endsExhausted_andOnlyTheLostOneOwesACancel` passes with and without the mutation
- approved: owner 2026-11-15

### 42. dev.ucplab.core.orchestrator.DefaultOrchestrator.onQuoteOutcome RemoveConditionalMutator_EQUAL_IF (`written.sessionId().isEmpty()` of the Uncertain case)
- description: removed conditional - replaced equality check with true
- why it is equivalent: for a create the intent written ahead has no session yet, so the operand is always true
- the test that shows it: the same test
- approved: owner 2026-11-15

### 43. dev.ucplab.core.orchestrator.DefaultOrchestrator.sendComplete RemoveConditionalMutator_EQUAL_IF (`approval.status() != CONSUMED`)
- description: removed conditional - replaced equality check with true (the approval is moved to CONSUMED even when it already is)
- why it is equivalent: `approvals.transition(APPROVED, CONSUMED)` is a compare-and-set on the expected status: for an approval that is already CONSUMED it changes nothing
- the test that shows it: `OrchestratorCompleteMutationTest.aPendingApprovalUnderAnApprovedIntent_isRepairedBeforeTheComplete_andARegularOneIsNot` passes with and without the mutation
- approved: owner 2026-11-15

### 44. dev.ucplab.core.orchestrator.DefaultOrchestrator.settle RemoveConditionalMutator_EQUAL_ELSE (the `unresolved.isEmpty()` return)
- description: removed conditional - replaced equality check with false
- why it is equivalent: `resolveAttempts` with no attempt writes nothing and only repeats the fencing check that the caller's following write makes anyway
- the test that shows it: `OrchestratorReconcileMutationTest.settling_leavesAnAnsweredAttemptAlone` passes with and without the mutation
- approved: owner 2026-11-15

### 45. dev.ucplab.core.orchestrator.DefaultOrchestrator.probeOrphanSession RemoveConditionalMutator_EQUAL_IF (`!unresolved.isEmpty()`)
- description: removed conditional - replaced equality check with true
- why it is equivalent: as the settle entry: `resolveAttempts` of an empty list writes nothing and repeats the fencing of the write-ahead that follows
- the test that shows it: `OrchestratorOwedCancelMutationTest.probes_areNumbered_andEachAttemptKeepsItsOwnStatus` passes with and without the mutation
- approved: owner 2026-11-15

## From H3 P8 (agent-ucp)

### 46. dev.ucplab.ucp.UcpHttpGateway.discover RemoveConditionalMutator_EQUAL_ELSE (the `now == null` operand of `failure.isPresent() || now == null`)
- description: removed conditional - replaced equality check with false
- why it is equivalent: justified: an untestable race. It is not equivalent: another thread can clear the cache (section 20, a 4xx of a create) between the refresh and the read, and the `now == null` operand is what keeps that from being a NullPointerException. The window lies between two adjacent statements, and no deterministic test can reach it without a test hook in production code
- the test that shows it: `UcpHttpGatewayMutationTest.aStaleProfile_isNeverServedAfterAFailedRefresh_exceptToACallInFlight` passes with and without the mutation (the `failure.isPresent()` operand is killed by it)
- approved: owner 2026-11-15

### 47. dev.ucplab.ucp.UcpHttpGateway.profileFor RemoveConditionalMutator_EQUAL_ELSE (the same `now == null` operand)
- description: removed conditional - replaced equality check with false
- why it is equivalent: justified: an untestable race. The same guard against the same cache clear, on the path of a call that needs a profile (see 46)
- the test that shows it: the same test
- approved: owner 2026-11-15

### 48. dev.ucplab.ucp.UcpHttpGateway.lambda$negotiate$0 RemoveConditionalMutator_EQUAL_IF (the `SUPPORTED_VERSIONS.contains(v)` operand)
- description: removed conditional - replaced equality check with true
- why it is equivalent: the constructor refuses a pinned version the agent does not support, so every element of `ucpVersions` is in `SUPPORTED_VERSIONS`; `ucpVersions.contains(v)` already implies it
- the test that shows it: `UcpHttpGatewayMutationTest.aProfileVersion_thatIsNotPinned_isIncompatible` (its first operand is killed) and the constructor refusal test pass with and without the mutation
- approved: owner 2026-11-15

### 49. dev.ucplab.ucp.UcpHttpGateway.normalizeHostPort MathMutator (`text.length() - 1` becomes `+ 1`)
- description: Replaced integer subtraction with addition
- why it is equivalent: `host:` (a colon last) is refused by this test with "an endpoint host is host:port"; without the test `Integer.parseInt("")` throws a NumberFormatException, which the catch turns into the very same IllegalArgumentException with the very same message
- the test that shows it: `UcpHttpGatewayMutationTest.aListedEndpointHost_mustBeHostAndPort_orABareHostForHttps` (the case `host:`) passes with and without the mutation
- approved: owner 2026-11-15

### 50. dev.ucplab.ucp.UcpHttpGateway.normalizeHostPort RemoveConditionalMutator_EQUAL_ELSE (the `colon == text.length() - 1` operand)
- description: removed conditional - replaced equality check with false
- why it is equivalent: as 49: the same input reaches the same exception through `parseInt`
- the test that shows it: the same test
- approved: owner 2026-11-15

### 51. dev.ucplab.ucp.UcpHttpGateway.refusalAsAnswer RemoveConditionalMutator_EQUAL_IF (the `session.isEmpty()` operand)
- description: removed conditional - replaced equality check with true/false (checked by hand: forcing it to false is green, to true is red)
- why it is equivalent: a `complete` always names its session: `complete(SessionId, ...)` is the only caller with `AttemptOp.COMPLETE` and takes a non-null id, so the guard is defensive. The other operands of the condition are killed
- the test that shows it: none possible through the public API (`UcpHttpGatewayMutationTest.aDeclineOrAStockRefusalOfComplete_isTheLabsAnswer_withTheLabsCode` passes with and without it)
- approved: owner 2026-11-15

### 52. dev.ucplab.ucp.UcpHttpGateway.send ConditionalsBoundaryMutator (`e.httpStatus() < 500` becomes `<= 500`)
- description: changed conditional boundary
- why it is equivalent: a ProtocolError with status 500 does not exist: the classifier answers every 5xx other than 501, 503 and 429 as Uncertain, and 501 is "unexpected", Uncertain for a create
- the test that shows it: `UcpHttpGatewayMutationTest.onlyA4xxOtherThan429_makesTheNextCreateReadTheProfileAgain` passes with and without the mutation
- approved: owner 2026-11-15

### 53. dev.ucplab.ucp.UcpHttpGateway.send RemoveConditionalMutator_ORDER_IF (the `e.httpStatus() >= 400` operand)
- description: removed conditional - replaced comparison check with true
- why it is equivalent: a ProtocolError of a create with a status below 400 does not exist: only a get answers an unexpected status as a ProtocolError, and a create is not a get (`newIntent` is true for creates only)
- the test that shows it: the same test
- approved: owner 2026-11-15

### 54. dev.ucplab.ucp.FailureClassifier.min ConditionalsBoundaryMutator (`d.compareTo(MAX_RETRY_AFTER) > 0` becomes `>= 0`)
- description: changed conditional boundary
- why it is equivalent: at exactly one day the two branches return equal durations (`MAX_RETRY_AFTER` and `d`)
- the test that shows it: `FailureClassifierMutationTest.aRetryAfterOfExactlyOneDay_isOneDay` passes with and without the mutation
- approved: owner 2026-11-15

### 55. dev.ucplab.ucp.RetryExecutor.backoff ConditionalsBoundaryMutator (`ceiling < cap` becomes `<=`)
- description: changed conditional boundary
- why it is equivalent: at `ceiling == cap` one more doubling gives twice the cap, which the following `Math.min(ceiling, cap)` brings back to the cap; the loop then ends. The result is the same for every retry
- the test that shows it: `RetryExecutorMutationTest.theBackoffBeforeRetryK_isInitialTimesTwoToTheKMinusOne_upToTheCap` passes with and without the mutation
- approved: owner 2026-11-15

### 56. dev.ucplab.ucp.RetryExecutor.retryDelay RemoveConditionalMutator_EQUAL_IF (the `op != COMPLETE` operand of the 429/503 case)
- description: removed conditional - replaced equality check with true
- why it is equivalent: `FailureClassifier` (final) answers a 429 or a 503 to a `complete` as Uncertain ("http 503 on complete", decision D), so a ProtocolError with that status never reaches this case for a complete; the operand is a second line of defence for the rule "complete is never retried" (I1, I7)
- the test that shows it: `RetryExecutorMutationTest.aLoneUncertainComplete_isReturnedAsClassified` and `RetryExecutorTest` (complete: one call on 429, 503 and Uncertain) pass with and without it; none possible for the ProtocolError itself, because the classifier cannot be made to produce it
- approved: owner 2026-11-15

### 57. dev.ucplab.ucp.RetryExecutor.tainted RemoveConditionalMutator_EQUAL_IF (the `!(result instanceof Attempt.Result r)` operand)
- description: removed conditional - replaced equality check with true/false
- why it is equivalent: every caller passes an `Attempt.Result`: a `Replied` returns before `tainted` is called (`loop` line 108), so the operand is always false
- the test that shows it: none possible (the `firstUncertain == null` operand of the same condition is killed by `aTaintedCall_namesTheFirstUncertainAttempt`)
- approved: owner 2026-11-15

### 58. dev.ucplab.ucp.RetryExecutor.tainted RemoveConditionalMutator_EQUAL_IF (the `last instanceof Uncertain` operand of `attempt == 1 && ...`)
- description: removed conditional - replaced equality check with true
- why it is equivalent: with `attempt == 1` and `firstUncertain != null` the one attempt was Uncertain, so the operand is true whenever the first one is
- the test that shows it: `RetryExecutorMutationTest.aLoneUncertainComplete_isReturnedAsClassified` passes with and without the mutation
- approved: owner 2026-11-15

### 59. dev.ucplab.ucp.RateLimiter.record ConditionalsBoundaryMutator (`wait.compareTo(MAX_BLOCK) > 0` becomes `>= 0`)
- description: changed conditional boundary
- why it is equivalent: at exactly `MAX_BLOCK` the two branches give equal durations
- the test that shows it: `LimitersMutationTest.theBlock_isCappedAtThirtySeconds_andOnlyA429WithARetryAfterBlocks` passes with and without the mutation
- approved: owner 2026-11-15

### 60. dev.ucplab.ucp.RateLimiter.tryAcquire BooleanFalseReturnValsMutator (the `return false;` for a blocked bucket)
- description: replaced boolean return with false
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 61. dev.ucplab.ucp.RateLimiter.tryAcquire BooleanFalseReturnValsMutator (the `return false;` for an empty bucket)
- description: replaced boolean return with false
- why it is equivalent: as 60
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 62. dev.ucplab.ucp.RateLimiter.tryAcquire BooleanTrueReturnValsMutator (the `return true;` after a token was taken)
- description: replaced boolean return with true
- why it is equivalent: the line already returns the constant true
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 68. dev.ucplab.ucp.Redactor.changed RemoveConditionalMutator_EQUAL_IF
- description: removed conditional - replaced equality check with true
- why it is equivalent: `changed` only decides whether `scrub` returns the same NotSent or Uncertain object or an equal copy of it; outcomes are records with value equality, and nothing in the system compares them by identity
- the test that shows it: `RedactorMutationTest.aCleanCause_comesBackEqual` passes with and without the mutation
- approved: owner 2026-11-15

### 69. dev.ucplab.ucp.Redactor.changed BooleanTrueReturnValsMutator
- description: replaced boolean return with true
- why it is equivalent: as 68
- the test that shows it: the same test
- approved: owner 2026-11-15

### 70. dev.ucplab.ucp.Redactor.scrub RemoveConditionalMutator_EQUAL_IF (the NotSent `changed(...)` test, "always rebuild")
- description: removed conditional - replaced equality check with true
- why it is equivalent: as 68: a rebuilt NotSent with the same cause is equal to the original
- the test that shows it: the same test (the "never rebuild" mutation of the same line is killed by `aNotSentCause_isScrubbed`)
- approved: owner 2026-11-15

### 71. dev.ucplab.ucp.Redactor.scrub RemoveConditionalMutator_EQUAL_IF (the Uncertain `changed(...)` test, "always rebuild")
- description: removed conditional - replaced equality check with true
- why it is equivalent: as 68
- the test that shows it: the same test (the "never rebuild" mutation is killed by `anUncertainCause_isScrubbed`)

## From H3 P9 (agent-persistence)
- approved: owner 2026-11-15

### 72. dev.ucplab.persistence.Database.close RemoveConditionalMutator_EQUAL_IF (the `closed.compareAndSet(false, true)` test)
- description: removed conditional - replaced equality check with true
- why it is equivalent: the body only closes the idle connections; run a second time (or by a second caller) the queue is already empty, so nothing is closed twice and nothing else changes
- the test that shows it: `DatabaseMutationTest.close_closesIdleConnections_andThoseThatComeBackLater` (it closes twice) passes with and without the mutation
- approved: owner 2026-11-15

### 73. dev.ucplab.persistence.Database.isUsable RemoveConditionalMutator_EQUAL_IF (the `!c.isClosed()` operand)
- description: removed conditional - replaced equality check with true
- why it is equivalent: with the operand forced true, a closed connection goes on to `c.getAutoCommit()`, which throws a SQLException on a closed connection, and the catch returns false: the same answer
- the test that shows it: `DatabaseMutationTest.aReadThatFails_keepsAUsableConnection_andReplacesABrokenOne` passes with and without the mutation
- approved: owner 2026-11-15

### 74. dev.ucplab.persistence.Database.isUsable BooleanTrueReturnValsMutator (the `return false;` in the `catch (SQLException)`, NO_COVERAGE)
- description: replaced boolean return with true
- why it is equivalent: justified: a catch the checked `SQLException` requires; unreachable with SQLite (`isClosed()` does not throw, and `getAutoCommit()` throws only on a closed connection, which the first operand has already answered)
- the test that shows it: none possible
- approved: owner 2026-11-15

### 75. dev.ucplab.persistence.Database.open VoidMethodCallMutator (the `db.close()` after a failed connect)
- description: removed call to dev/ucplab/persistence/Database::close
- why it is equivalent: a connect fails deterministically at the first connection (a path that cannot be opened), when no connection exists yet, so there is nothing for `close()` to close; the exception is the same
- the test that shows it: `DatabaseMutationTest.aFileThatCannotBeOpened_isRefused` passes with and without the mutation
- approved: owner 2026-11-15

### 76. dev.ucplab.persistence.Database.write RemoveConditionalMutator_EQUAL_IF (the `begun` operand of `healthy = begun && rollBack(c)`, in the SQLException catch)
- description: removed conditional - replaced equality check with true
- why it is equivalent: `begun` is false only when `setAutoCommit(false)` itself failed, which happens on a connection that is already broken; `rollBack` then fails as well and returns false, the same value. Checked by hand: forcing `begun` to true leaves every test green, forcing the rollback result to true is red
- the test that shows it: `DatabaseMutationTest.aStatementFailureThatCannotBeRolledBack_alsoReplacesTheConnection` passes with and without the mutation
- approved: owner 2026-11-15

### 77. dev.ucplab.persistence.Database.write RemoveConditionalMutator_EQUAL_IF (the same `begun` operand, in the RuntimeException catch)
- description: removed conditional - replaced equality check with true
- why it is equivalent: as 76
- the test that shows it: `DatabaseMutationTest.aConnectionThatCannotBeRolledBack_isClosedAndReplaced` passes with and without the mutation
- approved: owner 2026-11-15

### 78. dev.ucplab.persistence.Migrations.isCurrent RemoveConditionalMutator_EQUAL_IF (the `info.current() != null` operand)
- description: removed conditional - replaced equality check with true
- why it is equivalent: with no migration pending, at least one is applied (the classpath holds migrations), so `current()` is not null whenever the first operand is true
- the test that shows it: `MigrationsTest.isCurrent_onlyAfterEveryMigration_andNeverMigrates` passes with and without the mutation
- approved: owner 2026-11-15

### 79. dev.ucplab.persistence.Migrations.lambda$isCurrent$0 RemoveConditionalMutator_EQUAL_IF (the `m.getState().isApplied()` operand)
- description: removed conditional - replaced equality check with true
- why it is equivalent: `info.applied()` lists only migrations in an applied state (Flyway puts the others in `pending()`), so the operand is true for every element
- the test that shows it: `SqlPersistenceMutationTest.aFailedMigrationInTheHistory_isNotCurrent` (the `!isFailed()` operand is killed by it) passes with and without the mutation
- approved: owner 2026-11-15

## From H3 P10 (found by the final runs)

### 80. dev.ucplab.core.approval.DefaultApprovalService.decide RemoveConditionalMutator_EQUAL_IF (the `leaseOwner().equals(Optional.of(workerId))` operand of `tookLease`)
- description: removed conditional - replaced equality check with true
- why it is equivalent: a lease held live by another worker makes `claim` answer Busy before this line is reached, and a lease of another worker that has run out is not live, so the second operand already makes `tookLease` true. The operand cannot change the result for any state `decide` gets here with
- the test that shows it: `DecideReleasesItsLeaseTest.aLeaseTheWorkerAlreadyHeld_isNotReleased` and `busy_leavesTheHoldersLeaseAlone` pass with and without the mutation (the other operand is killed by them)
- approved: owner 2026-11-15

### 81. dev.ucplab.core.intent.InMemoryIntentRepository.releaseLease BooleanFalseReturnValsMutator (the `return false;` of a lease not held by the owner)
- description: replaced boolean return with false for dev/ucplab/core/intent/InMemoryIntentRepository::releaseLease
- why it is equivalent: the line already returns the constant false: the mutated return is the same value
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 82. dev.ucplab.core.intent.InMemoryIntentRepository.releaseLease BooleanTrueReturnValsMutator (the `return true;` after the release)
- description: replaced boolean return with true for dev/ucplab/core/intent/InMemoryIntentRepository::releaseLease
- why it is equivalent: the line already returns the constant true
- the test that shows it: none possible: the bytecode result is identical
- approved: owner 2026-11-15

### 83. dev.ucplab.persistence.SqlIntentRepository.insertAttempt VoidMethodCallMutator (the `setNull` of an absent http status)
- description: removed call to java/sql/PreparedStatement::setNull
- why it is equivalent: justified: SQLite binds an unbound parameter as NULL (every statement is opened fresh in a try-with-resources). PIT removes the call and keeps the evaluation of its argument `n++`, so the placeholder stays unbound and is bound as NULL (the same value `setNull` writes). Checked by hand: removing the call and its `n++` is red, removing the call alone is green
- the test that shows it: `IntentRepositoryContract.recordAttempt_keepsEveryFieldOfTheAttempt` (an attempt with no http status) passes with and without the mutation
- approved: owner 2026-11-15

### 84. dev.ucplab.persistence.SqlIntentRepository.updateAttempt VoidMethodCallMutator (the `setNull` of an absent http status)
- description: removed call to java/sql/PreparedStatement::setNull
- why it is equivalent: as 83: justified: SQLite binds an unbound parameter as NULL (every statement is opened fresh in a try-with-resources)
- the test that shows it: `IntentRepositoryContract.resolveAttempt*` (a resolve with no http status) pass with and without the mutation
- approved: owner 2026-11-15

### 85. dev.ucplab.persistence.SqlIntentRepository.lambda$claimRecoverable$14 RemoveConditionalMutator_EQUAL_ELSE (the `excluded.isEmpty()` test)
- description: removed conditional - replaced equality check with false
- why it is equivalent: with no excluded intent the mutant builds `AND id NOT IN ()`, which SQLite accepts and which is true for every row: the same rows are selected
- the test that shows it: `IntentRepositoryContract.claimRecoverable_*` (with and without an exclude set) pass with and without the mutation
- approved: owner 2026-11-15

### 86. dev.ucplab.persistence.SqlIntentRepository.storedSession RemoveConditionalMutator_EQUAL_IF (the `r.next()` test)
- description: removed conditional - replaced equality check with true
- why it is equivalent: justified: driver behaviour (sqlite-jdbc answers null before `next()`). For an intent with no row the mutant reads `getString(1)` before any `next()`, so the result is the same empty Optional. Checked by hand (the test below is green with the mutation)
- the test that shows it: `IntentRepositoryContract.resolveAttemptWithIntent_forAnUnknownIntent_isNotFound` passes with and without the mutation
- approved: owner 2026-11-15
