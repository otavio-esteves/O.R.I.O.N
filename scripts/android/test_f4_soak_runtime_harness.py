#!/usr/bin/env python3
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("orion_soak_runtime", Path(__file__).with_name("verify-f4-soak-runtime.py"))
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class SoakWorkloadTest(unittest.TestCase):
    fixture = "00000000-0000-0000-0000-000000000001"

    def test_short_and_full_manifests_are_explicit_finite_and_within_the_original_horizon(self):
        self.assertEqual([120,420], RECORDER.reminder_delays(600,300))
        self.assertEqual(6,len(RECORDER.reminder_delays(3600,600)))
        full=RECORDER.reminder_delays(86400,3600)
        self.assertEqual(24,len(full)); self.assertEqual(600,full[0]); self.assertEqual(83400,full[-1])

    def test_invalid_or_unbounded_workload_is_rejected(self):
        for duration,interval in [(599,300),(86401,3600),(3600,1),(True,300),(86400,300)]:
            with self.assertRaises(ValueError): RECORDER.reminder_delays(duration,interval)

    def test_checkpoint_modes_never_create_new_confirmation_during_verification(self):
        command=RECORDER.workload_command("reminder","verify",self.fixture,120)
        self.assertEqual("verify",command[command.index("orion_scheduled_mode")+1])
        self.assertNotIn("prepare",command)
        self.assertIn("soak",command)
        for kind,mode,fixture,delay in [("state","confirm",self.fixture,None),("state","verify",self.fixture,1),
                                       ("reminder","verify",self.fixture,-1),("reminder","prepare","../unsafe",120)]:
            with self.assertRaises(ValueError): RECORDER.workload_command(kind,mode,fixture,delay)

    def test_kill_is_exact_uid_scoped_and_cannot_become_force_stop_or_shell_injection(self):
        calls=[]
        RECORDER.terminate_main(lambda *args:calls.append(args),"123")
        self.assertEqual([("shell","run-as","network.orion","kill","-9","123")],calls)
        for pid in ["", "0", "123 456", "123;reboot", "123\n"]:
            with self.assertRaises(ValueError): RECORDER.terminate_main(lambda *args:self.fail("Invalid PID reached transport"),pid)

    def test_technical_snapshots_discard_raw_tags_and_other_apps(self):
        power="Wake Locks: size=2\n PARTIAL_WAKE_LOCK 'SECRET' (uid=10369 pid=999)\n PARTIAL_WAKE_LOCK 'OTHER_SECRET' (uid=1000) WS=WorkSource{42 other.app}\nSuspend Blockers: size=1\n"
        result=RECORDER.system_metrics("Thermal Status: 2\nSECRET",power,10369)
        self.assertEqual(1,result["appAttributedWakeLocks"])
        self.assertEqual(2,result["thermalStatus"])
        self.assertNotIn("SECRET",json.dumps(result))

    def test_unsupported_or_partial_system_dumps_are_not_qualified_as_zero(self):
        for thermal,power in [("unknown","Wake Locks: size=0"),("Thermal Status: 2","unknown"),
                              ("Thermal Status: 2","Wake Locks: size=1\nSuspend Blockers: size=0")]:
            with self.assertRaises(ValueError): RECORDER.system_metrics(thermal,power,10369)

    def test_full_control_flow_is_bounded_and_a_short_run_never_closes_the_gate(self):
        self.exercise_recorder()

    def test_interrupted_preparation_has_a_durable_journal_and_cleanup_never_verifies_delivery(self):
        self.exercise_recorder(interrupted=True)

    def test_optional_history_finishes_before_endpoint_instrumentation(self):
        self.exercise_recorder(maintenance_history=True)

    def test_missing_history_cannot_pass_and_still_restores_owned_fixtures(self):
        self.exercise_recorder(maintenance_history=True, history_available=False)

    def exercise_recorder(self, interrupted=False, maintenance_history=False, history_available=True):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); app=directory/"app-debug.apk"; test=directory/"app-debug-androidTest.apk"
            app.write_bytes(b"synthetic artifact"); test.write_bytes(app.read_bytes()); digest=hashlib.sha256(app.read_bytes()).hexdigest()
            clock=[0.0]; state={"power":"0","notifications":False,"exact":"default","timeout":"17000","killed":False,"passiveComplete":False,"historyComplete":False,"reminders":{}}
            observed=[]
            def run(command,**kwargs):
                args=command[3:]; output=""
                if args[:3] in (["shell","pm","grant"], ["shell","appops","set"]):
                    journal=json.loads((directory/"evidence/soak-recovery-journal.json").read_text())
                    self.assertEqual("17000",journal["originalPolicies"]["screenTimeout"])
                    self.assertEqual("INCOMPLETE",json.loads((directory/"evidence/f4-soak-runtime-report.json").read_text())["result"])
                if args[:3]==["shell","getprop","ro.kernel.qemu"]:output="0"
                elif args[:3]==["shell","getprop","ro.serialno"]:output="synthetic-device"
                elif args[:3]==["shell","getprop","ro.build.version.sdk"]:output="35"
                elif args[:3]==["shell","getprop","ro.build.fingerprint"]:output="synthetic/build"
                elif args==["shell","am","get-current-user"]:output="0"
                elif args[:3]==["shell","pm","path"]:output="package:/data/app/synthetic/base.apk"
                elif args[:2]==["shell","sha256sum"]:output=digest+"  /data/app/synthetic/base.apk"
                elif args[:3]==["shell","settings","get"]:output=state["power"] if args[-1]=="low_power" else state["timeout"]
                elif args[:3]==["shell","settings","put"]:state["timeout"]=args[-1]
                elif args[:3]==["shell","appops","get"]:output="SCHEDULE_EXACT_ALARM: "+state["exact"]
                elif args[:3]==["shell","appops","set"]:state["exact"]=args[-1]
                elif args[:3]==["shell","dumpsys","package"]:output="android.permission.POST_NOTIFICATIONS: granted="+str(state["notifications"]).lower()
                elif args[:2]==["shell","pm"]:state["notifications"]=args[2]=="grant"
                elif args[:4]==["shell","cmd","package","list"]:output="package:network.orion uid:10369"
                elif args[:3]==["shell","dumpsys","thermalservice"]:output="Thermal Status: 1"
                elif args[:3]==["shell","dumpsys","power"]:output="Wake Locks: size=0\nSuspend Blockers: size=0"
                elif args[:4]==["shell","cmd","power","set-mode"]:state["power"]=args[-1]
                elif args[:3]==["shell","am","instrument"]:
                    output="OK (1 test)\n"
                    if "orion_soak_mode" in args:
                        mode=args[args.index("orion_soak_mode")+1]
                        if mode=="prepare":self.assertTrue(json.loads((directory/"evidence/soak-recovery-journal.json").read_text())["stateCleanupRequired"])
                        output+="orion_soak_state="+mode.upper()
                    else:
                        item=args[args.index("orion_scheduled_fixture")+1];mode=args[args.index("orion_scheduled_mode")+1]
                        if mode=="prepare":
                            self.assertIn(item,[entry["fixtureId"] for entry in json.loads((directory/"evidence/soak-recovery-journal.json").read_text())["reminders"]])
                            if interrupted:raise KeyboardInterrupt()
                            state["reminders"][item]=int(args[args.index("orion_scheduled_delay_seconds")+1])
                        elif mode=="cleanup":
                            self.assertTrue(interrupted or not history_available)
                            output+="orion_scheduled_cleanup=OWNED_FIXTURE_REMOVED"
                        else:
                            self.assertIn(item,observed,"Verification cannot precede independent notification observation")
                            self.assertTrue(state["passiveComplete"],"Endpoint instrumentation must await the final passive sample")
                            if maintenance_history:self.assertTrue(state["historyComplete"])
                            output+="REAL_NOTIFICATION_AND_COMMITTED_FIRED"
                elif args[:3]==["shell","run-as","network.orion"]:
                    if args[3]=="cat":
                        if "orion_soak_snapshot_" in args[-1]:output=json.dumps({"queues":{},"instrumentationCheckpoint":True})
                        else:
                            item=args[-1].removeprefix("files/orion_scheduled_runtime_").removesuffix(".json")
                            output=json.dumps({"fixtureId":item,"reminderId":item,"actionId":item,"normalizedPayloadHash":"a"*64,"confirmationFingerprint":"b"*64})
                    elif args[3]=="kill":state["killed"]=True
                elif args[:2]==["shell","pidof"]:
                    output="123" if not state["killed"] else "456" if clock[0]>=420 else ""
                elif args[:4]==["shell","cmd","notification","list"]:
                    active=[item for item,due in state["reminders"].items() if clock[0]>=due]
                    observed.extend(x for x in active if x not in observed)
                    output="\n".join("0|network.orion|0|"+item+"|10369" for item in active)
                elif args[:3]!=["shell","input","keyevent"]:self.fail("Unexpected synthetic command: "+repr(args))
                return subprocess.CompletedProcess(command,0,output,"")
            class Telemetry:
                def __init__(self,command,**kwargs):
                    path=Path(command[command.index("--evidence-dir")+1]);path.mkdir()
                    self.history="record-maintenance-history.py" in command[1]
                    if self.history:
                        (path/"maintenance-history-report.json").write_text(json.dumps({"result":"HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED",
                            "queueHistoryAvailable":history_available,"heartbeatPassesFirstSeenAfterBaseline":0,
                            "continuousCoverageProven":False,"heartbeatAutonomyProven":False,"historySha256":"c"*64}))
                    else:(path/"soak-observation-report.json").write_text(json.dumps({"result":"OBSERVATION_COMPLETE_REVIEW_REQUIRED"}))
                def poll(self):return 0
                def wait(self,**kwargs):state["historyComplete" if self.history else "passiveComplete"]=True;return 0
            argv=[RECORDER.__file__,"--adb","synthetic-adb","--serial","synthetic","--environment","PHYSICAL_DEVICE",
                  "--app-apk",str(app),"--test-apk",str(test),"--evidence-dir",str(directory/"evidence"),
                  "--duration-seconds","600","--reminder-interval-seconds","300","--dedicated-target"]
            if maintenance_history:argv.append("--maintenance-history")
            with patch("sys.argv",argv),patch.object(RECORDER.subprocess,"run",run),patch.object(RECORDER.subprocess,"Popen",Telemetry), \
                 patch.object(RECORDER.time,"monotonic",lambda:clock[0]),patch.object(RECORDER.time,"sleep",lambda seconds:clock.__setitem__(0,clock[0]+seconds)),patch("sys.stdout",io.StringIO()):
                self.assertEqual(1 if interrupted or not history_available else 0,RECORDER.main())
            report=json.loads((directory/"evidence/f4-soak-runtime-report.json").read_text())
            self.assertEqual("INCOMPLETE" if interrupted or not history_available else "SHORT_RUN_COMPLETE_REVIEW_REQUIRED",report["result"])
            self.assertFalse(report["fullSoakGateClosed"]);self.assertFalse(report["eligibleFor24HourReview"])
            if interrupted:
                self.assertEqual("KeyboardInterrupt",report["failureType"])
                self.assertEqual(["PASS"],list(report["reminderFixtureCleanup"].values()))
            else:
                self.assertEqual(2,len(report["observedNotifications"]))
                self.assertEqual("RUN_AS_SIGKILL",report["controlledProcessDeath"]["method"])
            self.assertEqual("17000",state["timeout"]);self.assertFalse(state["notifications"]);self.assertEqual("default",state["exact"])


if __name__=='__main__':unittest.main()
