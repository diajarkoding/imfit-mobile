"""Host-only tests for the local test runner's fail-closed target guards."""

import contextlib
import io
import json
import unittest
from unittest.mock import patch

import verify_local_baseline as runner


class TargetGuardTest(unittest.TestCase):
    CONTEXT = [{"Endpoints": {"docker": {"Host": "unix:///var/run/docker.sock"}}}]
    CONTAINER = [{"Config": {"Labels": {"com.supabase.cli.project": runner.PROJECT}}, "State": {"Running": True}}]
    METADATA = {
        "database": "postgres", "server_version_num": "170006",
        "migration_history": "supabase_migrations.schema_migrations",
        "storage_buckets": "storage.buckets", "app_templates": "public.workout_templates",
    }

    def setUp(self):
        self.environment = patch.dict(runner.os.environ, {}, clear=False)
        self.environment.start()
        runner.os.environ.pop("DOCKER_HOST", None)
        self.addCleanup(self.environment.stop)
        self.docker = patch.object(runner, "docker_path", return_value="docker-test")
        self.docker.start()
        self.addCleanup(self.docker.stop)

    def test_endpoint_override_is_rejected_before_any_docker_call(self):
        with patch.dict(runner.os.environ, {"DOCKER_HOST": "tcp://example.invalid:2375"}):
            with patch.object(runner, "command") as command:
                with self.assertRaisesRegex(RuntimeError, "DOCKER_HOST"):
                    runner.verify()
                command.assert_not_called()

    def test_remote_context_is_rejected_before_container_inspection(self):
        context = [{"Endpoints": {"docker": {"Host": "ssh://example.invalid"}}}]
        with patch.object(runner, "command", return_value=json.dumps(context)) as command:
            with self.assertRaisesRegex(RuntimeError, "not a local socket"):
                runner.verify()
            self.assertEqual(command.call_count, 1)

    def test_wrong_project_container_is_rejected_before_sql(self):
        context = [{"Endpoints": {"docker": {"Host": "unix:///var/run/docker.sock"}}}]
        container = [{"Config": {"Labels": {"com.supabase.cli.project": "other-project"}}, "State": {"Running": True}}]
        with patch.object(runner, "command", side_effect=[json.dumps(context), json.dumps(container)]) as command:
            with self.assertRaisesRegex(RuntimeError, "label mismatch"):
                runner.verify()
            self.assertEqual(command.call_count, 2)

    def test_wrong_baseline_is_rejected_before_fixture(self):
        responses = [json.dumps(self.CONTEXT), json.dumps(self.CONTAINER), json.dumps(self.METADATA), "[]"]
        with patch.object(runner, "command", side_effect=responses) as command:
            with self.assertRaisesRegex(RuntimeError, "four-migration baseline"):
                runner.verify()
            self.assertEqual(command.call_count, 4)
            self.assertIn("begin read only", command.call_args.args[1].lower())

    def test_partial_startup_is_rejected_before_migration_read_or_fixture(self):
        metadata = dict(self.METADATA, migration_history=None, storage_buckets=None)
        responses = [json.dumps(self.CONTEXT), json.dumps(self.CONTAINER), json.dumps(metadata)]
        with patch.object(runner, "command", side_effect=responses) as command:
            with self.assertRaisesRegex(RuntimeError, "initialization/migration history is incomplete"):
                runner.verify()
            self.assertEqual(command.call_count, 3)

    def test_mocked_success_labels_the_limited_baseline_scope(self):
        audit = {
            "transaction_read_only": "on", "template_revision_column": True,
            "template_receipt_table": "template_write_receipts", "public_tables_without_rls": [],
            "missing_required_routine_names": [f"mock_rpc_{i}" for i in range(15)],
        }
        output = io.StringIO()
        responses = [json.dumps(self.CONTEXT), json.dumps(self.CONTAINER), json.dumps(self.METADATA), json.dumps(runner.MIGRATIONS), "", json.dumps(audit)]
        with patch.object(runner, "command", side_effect=responses) as command:
            with contextlib.redirect_stdout(output):
                runner.verify()
            result = json.loads(output.getvalue())
            self.assertEqual(result["status"], "PASS")
            self.assertIn("baseline only", result["scope"])
            self.assertEqual(command.call_count, 6)
            fixture_sql = command.call_args_list[4].args[1].lower()
            self.assertIn("begin;", fixture_sql)
            self.assertIn("rollback;", fixture_sql)


if __name__ == "__main__":
    unittest.main()
