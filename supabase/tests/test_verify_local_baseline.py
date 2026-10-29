"""Host-only tests for the local test runner's fail-closed target guards."""

import json
import unittest
from unittest.mock import patch

import verify_local_baseline as runner
import verify_local_migration_paths as replay


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



class PlatformDumpGuardTest(unittest.TestCase):
    ROLES = {"postgres", "supabase_admin", "supabase_auth_admin", "supabase_storage_admin"}

    def toc(self, extra=(), omitted=()):
        objects = sorted(replay.APP_TOC_OBJECTS - set(omitted))
        objects.extend(extra)
        return "; Archive created by guarded local pg_dump\n" + "\n".join(
            f"{number}; 0 0 {item}" for number, item in enumerate(objects, 1))

    def test_platform_objects_retained_and_only_exact_app_dependencies_excluded(self):
        schema = "SCHEMA - auth supabase_auth_admin"
        extension = "EXTENSION - pgcrypto "
        comment = "COMMENT - EXTENSION pgcrypto "
        trigger = "TRIGGER storage buckets enforce_bucket_name_length_trigger supabase_storage_admin"
        result = replay.filter_platform_toc(self.toc([schema, extension, comment, trigger]), self.ROLES)
        self.assertIn(schema, result)
        self.assertIn(extension, result)
        self.assertIn(comment, result)
        self.assertIn(trigger, result)
        self.assertNotIn("avatars_", result)
        self.assertNotIn("on_auth_user_created", result)

    def test_unknown_storage_policy_is_blocked_not_silently_filtered(self):
        with self.assertRaisesRegex(RuntimeError, "Unknown app-dependent"):
            replay.filter_platform_toc(self.toc(["POLICY storage objects avatars_unreviewed postgres"]), self.ROLES)

    def test_unknown_auth_user_trigger_is_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "Unknown app-dependent"):
            replay.filter_platform_toc(self.toc(["TRIGGER auth users unreviewed postgres"]), self.ROLES)

    def test_schema_dump_containing_data_is_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "platform-data"):
            replay.filter_platform_toc(self.toc(["TABLE DATA auth users supabase_auth_admin"]), self.ROLES)

    def test_missing_or_duplicate_allowlisted_object_is_blocked(self):
        omitted = next(iter(replay.APP_TOC_OBJECTS))
        with self.assertRaisesRegex(RuntimeError, "missing exact"):
            replay.filter_platform_toc(self.toc(omitted=[omitted]), self.ROLES)
        with self.assertRaisesRegex(RuntimeError, "Duplicate"):
            replay.filter_platform_toc(self.toc([omitted]), self.ROLES)

    def test_unavailable_platform_owner_fails_with_concrete_role(self):
        with self.assertRaisesRegex(RuntimeError, "unavailable role: unexpected_admin"):
            replay.filter_platform_toc(self.toc(["SCHEMA - auth unexpected_admin"]), self.ROLES)

    def test_unrecognized_toc_format_is_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "Unrecognized"):
            replay.filter_platform_toc(self.toc() + "\nmalformed archive record", self.ROLES)


if __name__ == "__main__":
    unittest.main()
