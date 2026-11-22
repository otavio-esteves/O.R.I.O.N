"""Strict model-free AndroidJUnitRunner result validation; no raw output in reports."""
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

SELECTED = {
    'network.orion.app.chat.OrionChatScreenTest': {
        'actualChatFastPathsAndDurableExactConfirmationWorkWithoutCortex',
        'rejectedTaskDraftRemainsEditableAndCanBeCorrected',
    },
    'network.orion.app.chat.OrionChatInputScreenTest': {
        'admittedSendClearsOnlyTheEditorAndAllowsADeliberateIdenticalRequest',
        'rejectedTaskTitleRemainsExactlyEditableWithoutHistoryOrProcessing',
        'emptyInputHasItsOwnErrorAndNoProcessing',
        'oversizedUtf8PastesPreserveThePreviousDraftAndExplainTheRejectedEdit',
        'invalidBoundedTextIsNotSilentlyRewrittenOnEditOrRejectedSend',
        'closedControllerKeepsTheDraftAndReportsSessionClosure',
        'busyControllerDisablesSendWithoutClearingDraftOrDuplicatingAnAdmittedTurn',
    },
}
F8_SELECTED = {
    'network.orion.app.sentinel.OrionSentinelRuntimeTest': {
        'heartbeatPostsOneGenericSuggestionAndPersistsFeedback',
        'quietHoursAndTopicCooldownDoNotReserveOtherTasks',
        'unacknowledgedSuggestionRecordsIgnoredOnlyOnce',
        'oneRemainingOutboxSlotCommitsDeliveryAndShown',
        'committedSnoozeIsReconciledWithoutAnotherCallback',
        'expiredPrepareDoesNotStarveLaterTasks',
        'snoozeWorksForVisibleNotificationWhenShownCommitFailed',
        'expiredReservationsReleaseCapacityWithoutAllowingClockRollbackReplay',
    },
    'network.orion.app.sentinel.OrionProactiveDeliveryTest': {
        'exactPolicyBindingCommitsOnePostAndOneFact',
        'missingOrReplacedConsentCannotAuthorizeAnEffect',
        'optOutImmediatelyBeforePostingLeavesNoNotification',
        'ambiguousEffectIsNotRetriedAndCannotPublishShown',
        'failureToRecordShownRollsBackSuccessAndFinishesUnknown',
        'expiredUnclaimedDraftIsCancelledWithoutPosting',
    },
    'network.orion.app.memories.OrionMemoryKernelTest': {
        'gatedQueuePersistsAndOnlyPreparesAfterRelease',
        'actualIngressCommitsConfirmationMemoryEventSuccessAndReplayTogether',
        'expiredQueuedConsentIsRejectedWithoutRenewingOrConfirming',
        'capabilityLossRollsBackProcessingAndPreservesOriginalReceipt',
        'durableOriginSurvivesReopenButFreshFocusedPresenceIsStillRequired',
        'wrongProducerCannotManufactureUserOrigin',
        'unreadablePendingRequestCanOnlyBeDiscardedWithFreshPresence',
        'alteredFingerprintAndOverlongReceiptCannotAuthorizeEffect',
        'databaseFailureRollsBackEnclosingIngressAndAllowsExplicitSafeReplay',
        'correctionBindsTheReplacementAndTheOldVersionAndErasesItsCopiesWhenForgotten',
        'reviewOnlyConfirmsTheSelectedInferredClaimAndKeepsItsOriginAndConfidence',
        'archivedConversationRequiresExactOptInAndForgetErasesEveryDurableCopy',
        'indexingRequiresExplicitChoiceBoundToTheConfirmedWrite',
        'ownedCreationFixesProvenanceAndForgetErasesEntityClaimsAndEveryPersistedContentCopy',
        'forgottenReplayWithFreshProducerKeyNeverPersistsAnotherCopyOrResurrects',
        'forgetCancelsAndErasesOtherCreationDraftsForTheExactTargetOnly',
        'forgetPagesBeyondTenThousandHistoricalActionsWithoutLeavingTargetCiphertext',
        'pendingProposalLimitRejectsNewWorkButPreservesReplayAndExplicitDismissal',
        'retainedProposalLimitStillAllowsConfirmationAndForgetOfAnExistingMemory',
        'erasureFailureRollsBackDeletionClaimRemovalTombstoneAndEarlierCiphertextScrubs',
        'missingPrivacyTombstoneCannotTurnCorruptTerminalEnvelopesIntoForgottenProof',
        'enqueueBlockedBehindForgetWriterObservesCommittedTombstoneBeforePersistingAnyCopy',
        'inferredExistingMemoryCannotBePromotedThroughUserCreateReplayOrShownAsDeclared',
    },
    'network.orion.app.memories.OrionMemorySearchIndexTest': {
        'protectedBytesNeverEnterFtsAndRemovalFollowsSupersessionAndDeletion',
        'upgradeRemovesLegacyPlaintextWhilePreservingReadablePrivateMemory',
        'deletingIndexedMemoryRemovesTermsFromPersistedFtsSegments',
        'upgradeFromElevenRebuildsOnlyAuthorizedIndexContent',
        'backfillResumesAfterKeyFailureAndReclassificationRemovesPosting',
        'searchUsesAuthoritativeValidityAndStableRankingAcrossPages',
        'selectedContextCannotUseAMemoryDeletedAfterTheSearch',
        'stalePostingWithoutSearchConsentCannotEnterResultsOrChatContext',
    },
    'network.orion.app.scheduling.OrionSchedulerPlatformTest': {
        'approximateRegistrationCreatesImmutableExplicitIdentityAndCancellationRemovesIt',
        'replayReplacesSameArtifactWhileStaleCancellationCannotCancelNewGeneration',
        'exactCapabilityReflectsCurrentPlatformAccessWithoutChangingPermission',
        'schedulerComponentsArePrivateMainOwnedAndAutomaticWorkInitializerIsAbsent',
        'repeatedRecoveryKeepsOneDeferrableHalfHourHeartbeat',
        'recoveredMainOwnsInitializedWorkManager',
    },
    'network.orion.app.UpgradePersistenceTest': {
        'installedUpgradePreservesEncryptedRecordsAndUncertainActions',
    },
}


F9_SELECTED = {
    'network.orion.app.voice.OrionCoreGatewayServiceTest': {
        'privateMainGatewayNegotiatesAndReportsReadiness',
        'callbackStreamsOneTerminalResponseForAnAcceptedRequest',
    },
    'network.orion.app.voice.OrionVoiceIngressTest': {
        'partialNeverEnqueuesAndRecoveryProcessesFinalOnlyOnce',
    },
    'network.orion.app.voice.OrionVoiceTaskAdmissionTest': {
        'finalVoiceRequestPreparesButDoesNotExecuteTask',
    },
    'network.orion.app.voice.OrionVoiceTopologyTest': {
        'voiceComponentsArePrivateAndRolesAreSeparated',
    },
}


F10_SELECTED = {
    'network.orion.app.voice.OrionSystemAssistantPlatformTest': {
        'systemAssistantDeclaresOnlyItsRequiredProtectedServices',
        'coreRespondsWithoutAnAssistantRole',
    },
}


F11_SELECTED = {
    'network.orion.app.voice.OrionWakeWordPlatformTest': {
        'diagnosticsReportsWakeWordDisabledWhilePhysicalQualificationIsDeferred',
        'packagedVoiceComponentsExposeNoHotwordActivationPath',
    },
}

F12_SELECTED = {
    'network.orion.app.adaptive.OrionAdaptiveIntelligenceTest': {
        'roomTwelveRetainsProactivityConsentAndDefaultsPersonalizationOff',
        'consentIsGatedAndResetsItsFeedbackWindowAcrossClockRollback',
        'learnedDeferralDoesNotReserveTaskAndRevocationRestoresBaseline',
        'revocationSurvivesBothCapacityLimitsAndSupersedesQueuedOptIn',
        'rankingPreferenceReordersEligibleMemoriesAndInvalidatesOldCursor',
        'settingsExposeAnIndependentReversibleOptIn',
    },
}

def test_identities(selection):
    return frozenset((cls, method) for cls, methods in selection.items() for method in methods)


EXPECTED = test_identities(SELECTED)


@dataclass
class Result:
    started: set = field(default_factory=set)
    passed: set = field(default_factory=set)
    failed: set = field(default_factory=set)
    skipped: set = field(default_factory=set)
    errors: list = field(default_factory=list)
    expected: frozenset = EXPECTED

    @property
    def successful(self):
        return not self.errors and self.started == self.expected and self.passed == self.expected

    def summary(self):
        return dict(expected=len(self.expected), started=len(self.started), passed=len(self.passed),
                    failed=len(self.failed), skipped=len(self.skipped), errors=self.errors,
                    result='PASS' if self.successful else 'FAIL')


def parse_instrumentation(output, adb_exit, expected=EXPECTED):
    result = Result(expected=expected)
    frame = {}
    active = None
    terminal = set()
    final = []
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator and key in ('class', 'test', 'numtests', 'current'):
                if key in frame:
                    result.errors.append('DUPLICATE_FRAME_FIELD')
                frame[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            try:
                code = int(line.split(': ', 1)[1])
            except ValueError:
                result.errors.append('INVALID_STATUS_CODE')
                continue
            identity = frame.get('class'), frame.get('test')
            if final:
                result.errors.append('STATUS_AFTER_FINAL')
            if identity not in expected:
                result.errors.append('UNEXPECTED_TEST')
            elif frame.get('numtests') != str(len(expected)):
                result.errors.append('TEST_COUNT_MISMATCH')
            elif code == 1:
                if active is not None or identity in result.started:
                    result.errors.append('DUPLICATE_OR_OVERLAPPING_START')
                if frame.get('current') != str(len(result.started) + 1):
                    result.errors.append('INVALID_TEST_SEQUENCE')
                result.started.add(identity)
                active = identity
            elif code in (0, -1, -2, -3, -4):
                if active != identity or identity in terminal:
                    result.errors.append('UNOWNED_OR_DUPLICATE_TERMINAL')
                if frame.get('current') != str(len(result.started)):
                    result.errors.append('INVALID_TEST_SEQUENCE')
                terminal.add(identity)
                active = None
                if code == 0:
                    result.passed.add(identity)
                elif code in (-3, -4):
                    result.skipped.add(identity)
                else:
                    result.failed.add(identity)
            else:
                result.errors.append('INVALID_STATUS_CODE')
            frame = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            final.append(line.partition(': ')[2])
        elif line.startswith('INSTRUMENTATION_FAILED:') or line.startswith('INSTRUMENTATION_RESULT: shortMsg='):
            result.errors.append('INSTRUMENTATION_FAILURE')
    if adb_exit != 0:
        result.errors.append('ADB_FAILED')
    if final != ['-1']:
        result.errors.append('MISSING_OR_INVALID_FINAL_CODE')
    if frame or active is not None or result.started != expected or terminal != expected:
        result.errors.append('INCOMPLETE_CASES')
    if result.failed:
        result.errors.append('TEST_FAILURE')
    if result.skipped:
        result.errors.append('TEST_SKIPPED')
    summaries = re.findall(r'^OK \((\d+) tests?\)\s*$', output, re.MULTILINE)
    if summaries != [str(len(expected))]:
        result.errors.append('MISSING_OR_INVALID_SUCCESS_SUMMARY')
    return result


def junit_report(result):
    """Only allowlisted test identities and fixed technical codes; no stack/stream/content."""
    protocol_error = bool(set(result.errors) - {'TEST_FAILURE', 'TEST_SKIPPED', 'MISSING_OR_INVALID_SUCCESS_SUMMARY'})
    if 'MISSING_OR_INVALID_SUCCESS_SUMMARY' in result.errors and not (result.failed or result.skipped):
        protocol_error = True
    suite = ET.Element('testsuite', name='orion_android_no_model', tests=str(len(result.expected)),
                       failures=str(len(result.failed)), skipped=str(len(result.skipped)),
                       errors=str(len(result.expected - result.failed - result.skipped) if protocol_error else 0))
    for cls, method in sorted(result.expected):
        case = ET.SubElement(suite, 'testcase', classname=cls, name=method)
        if (cls, method) in result.failed:
            ET.SubElement(case, 'failure', message='TEST_FAILURE')
        elif (cls, method) in result.skipped:
            ET.SubElement(case, 'skipped', message='TEST_SKIPPED')
        elif protocol_error:
            ET.SubElement(case, 'error', message=','.join(sorted(set(result.errors))) or 'INCOMPLETE_CASES')
    return ET.tostring(suite, encoding='unicode') + '\n'
