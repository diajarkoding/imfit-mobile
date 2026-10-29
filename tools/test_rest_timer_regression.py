import unittest

from rest_timer_regression import analyze_cold, analyze_warm_transport, summarize


class TimingAnalysisTest(unittest.TestCase):
    def test_correlates_generation_source_and_elapsed_deadline(self):
        rows = summarize("""
I IMFITRest: event=alarm_scheduled restInstanceId=a generation=1 now=10 deadlineElapsedMs=100 appliedMethod=EXACT_ALLOW_IDLE
I IMFITRest: event=receiver_received restInstanceId=a generation=0 now=102 receivedDeadlineElapsedMs=50
I IMFITRest: event=receiver_received restInstanceId=a generation=1 now=103
I IMFITRest: event=expiry_requested restInstanceId=a generation=1 now=104 source=alarm
I IMFITRest: event=expiry_requested restInstanceId=a generation=1 now=105 source=ui_resume
I IMFITRest: event=expired_committed restInstanceId=a generation=1 now=110 source=alarm
I IMFITRest: event=notify_result restInstanceId=a generation=1 now=115 success=true
""")
        current = next(row for row in rows if row["generation"] == "1")
        self.assertEqual(3, current["alarmLatenessMs"])
        self.assertEqual(10, current["commitLatenessMs"])
        self.assertEqual(15, current["notificationLatenessMs"])
        self.assertEqual(6, current["processingDelayMs"])
        self.assertEqual(1, current["commitCount"])
        stale = next(row for row in rows if row["generation"] == "0")
        self.assertEqual(0, stale["commitCount"])
        self.assertIsNone(stale["commitLatenessMs"])

    def test_missing_and_failed_notifications_are_not_zero_latency(self):
        row = summarize("""
I IMFITRest: event=alarm_scheduled restInstanceId=a generation=0 now=10 deadlineElapsedMs=100
I IMFITRest: event=notify_result restInstanceId=a generation=0 now=115 success=false
""")[0]
        self.assertIsNone(row["alarmLatenessMs"])
        self.assertIsNone(row["notificationLatenessMs"])
        self.assertIsNone(row["processingDelayMs"])

    def test_duplicate_commits_are_visible(self):
        row = summarize("""
I IMFITRest: event=expired_committed restInstanceId=a generation=0 now=100 deadlineElapsedMs=100 source=alarm
I IMFITRest: event=expired_committed restInstanceId=a generation=0 now=101 deadlineElapsedMs=100 source=alarm
""")[0]
        self.assertEqual(2, row["commitCount"])


class ColdRecoveryAnalysisTest(unittest.TestCase):
    armed = dict(restInstanceId="fixture", generation="2", pid="100",
                 deadlineElapsedMs="10000", now="1000")

    def snapshot(self, phase, now=13000, scenario="cold-offline", extra=""):
        return (f"I IMFITRestTest: event=test_snapshot scenario={scenario} phase={phase} "
                f"restInstanceId=fixture generation=2 pid=200 now={now} "
                f"deadlineElapsedMs=10000 {extra}\n")

    def offline_proof(self):
        return self.snapshot("offline_verified", extra="status=RUNNING anchorRecoverable=true")

    def online_proof(self):
        return (self.snapshot("session_verified", now=2000, scenario="cold-online") +
                "I IMFITRest: event=expired_committed restInstanceId=fixture generation=2 "
                "pid=200 now=10000 deadlineElapsedMs=10000 source=ui_resume\n" +
                self.snapshot("online_verified", now=10001, scenario="cold-online"))

    def test_offline_proof_requires_elapsed_deadline_and_recoverable_anchor(self):
        self.assertEqual(0, analyze_cold(self.offline_proof(), self.armed, "cold-offline")["commitCount"])
        for text in ("", self.offline_proof().replace("now=13000", "now=12999"),
                     self.offline_proof().replace("anchorRecoverable=true", "anchorRecoverable=false"),
                     self.offline_proof().replace("status=RUNNING", "status=EXPIRED")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                analyze_cold(text, self.armed, "cold-offline")

    def test_offline_proof_rejects_any_business_completion_or_audio(self):
        for event in ("expired_committed", "audio_started", "notify_result success=true"):
            text = self.offline_proof() + (
                f"I IMFITRest: event={event} restInstanceId=fixture generation=2 now=10001\n")
            with self.subTest(event=event), self.assertRaises(AssertionError):
                analyze_cold(text, self.armed, "cold-offline")

    def test_unrelated_generation_does_not_poison_offline_anchor(self):
        text = self.offline_proof() + (
            "I IMFITRest: event=expired_committed restInstanceId=fixture generation=1 now=10000\n")
        self.assertEqual(0, analyze_cold(text, self.armed, "cold-offline")["commitCount"])

    def test_cold_proof_requires_new_pid_and_original_deadline(self):
        for text in (self.offline_proof().replace("pid=200", "pid=100"),
                     self.offline_proof().replace("deadlineElapsedMs=10000", "deadlineElapsedMs=14000"),
                     self.offline_proof().replace("generation=2", "generation=3")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                analyze_cold(text, self.armed, "cold-offline")

    def test_online_commit_follows_server_verification_at_original_deadline(self):
        proof = analyze_cold(self.online_proof(), self.armed, "cold-online")
        self.assertEqual(1, proof["commitCount"])
        self.assertEqual(10000, proof["originalDeadlineElapsedMs"])

    def test_online_rejects_unverified_early_duplicate_or_reset_expiry(self):
        text = self.online_proof()
        lines = text.splitlines(keepends=True)
        for invalid in ("".join(lines[1:]), "".join([lines[1], lines[0], lines[2]]),
                        text.replace("now=10000", "now=9999"), text + lines[1],
                        text + "I IMFITRest: event=alarm_scheduled restInstanceId=fixture "
                        "generation=2 now=3000 deadlineElapsedMs=15000\n"):
            with self.subTest(text=invalid), self.assertRaises(AssertionError):
                analyze_cold(invalid, self.armed, "cold-online")


class WarmTransportAnalysisTest(unittest.TestCase):
    hot = dict(restInstanceId="fixture", generation="2", runId="run", now="1000", peekCount="10000")
    after = dict(restInstanceId="fixture", generation="2", runId="run", now="61000")
    start = 1_000_000_000
    end = 61_000_000_000

    def trace(self):
        return [
            dict(kind="http", category="rpc", path="/rest/v1/rpc/create_active_session",
                 hostMonotonicNs=1, status=200),
            dict(kind="phase", phase="hot_peeks", hostMonotonicNs=self.start),
            dict(kind="phase", phase="after_deadline", hostMonotonicNs=self.end),
        ]

    def test_real_minute_excludes_auth_setup_and_cleanup(self):
        trace = self.trace() + [
            dict(kind="http", category="auth", hostMonotonicNs=self.start + 1, status=200),
            dict(kind="http", category="rpc", hostMonotonicNs=self.end + 1, status=200),
        ]
        proof = analyze_warm_transport(trace, self.hot, self.after)
        self.assertEqual(60000, proof["hostObservationDurationMs"])
        self.assertEqual(60000, proof["deviceObservationDurationMs"])
        self.assertEqual(0, proof["businessRequestCount"])
        self.assertEqual(1, proof["excludedAuthRequestCount"])

    def test_business_arrivals_fail_even_without_successful_response(self):
        for category in ("rpc", "table", "storage"):
            for timestamp in (self.start, self.start + 1, self.end):
                with self.subTest(category=category, timestamp=timestamp), self.assertRaises(AssertionError):
                    analyze_warm_transport(self.trace() + [
                        dict(kind="http", category=category, hostMonotonicNs=timestamp, status=None)
                    ], self.hot, self.after)

    def test_hot_loop_without_elapsed_time_is_not_runtime_proof(self):
        with self.assertRaises(AssertionError):
            analyze_warm_transport(self.trace(), self.hot, dict(self.after, now="60999"))
        short_host = self.trace()
        short_host[-1]["hostMonotonicNs"] = self.end - 1
        with self.assertRaises(AssertionError):
            analyze_warm_transport(short_host, self.hot, self.after)
        with self.assertRaises(AssertionError):
            analyze_warm_transport(self.trace(), dict(self.hot, peekCount="9999"), self.after)

    def test_missing_proxy_traffic_or_phase_cannot_pass(self):
        for trace in (self.trace()[1:], self.trace()[:-1], self.trace() + [self.trace()[-1]]):
            with self.subTest(trace=trace), self.assertRaises(AssertionError):
                analyze_warm_transport(trace, self.hot, self.after)
        with self.assertRaises(AssertionError):
            analyze_warm_transport(self.trace(), self.hot, dict(self.after, generation="3"))


if __name__ == "__main__":
    unittest.main()
