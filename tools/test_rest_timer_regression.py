import unittest

from rest_timer_regression import summarize


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


if __name__ == "__main__":
    unittest.main()
