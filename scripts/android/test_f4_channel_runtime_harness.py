#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("orion_channel", Path(__file__).with_name("verify-f4-channel-runtime.py"))
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class ChannelRecorderTest(unittest.TestCase):
    def page(self, checked="true", bounds="[800,100][1000,200]", extra=""):
        return ('<hierarchy><node text="O.R.I.O.N."/><node text="Lembretes"/>'
                '<node package="com.android.settings" resource-id="com.android.settings:id/switch_bar">'
                '<node checkable="true" checked="'+checked+'" bounds="'+bounds+'"/>'+extra+'</node></hierarchy>')

    def test_native_main_control_is_selected_by_exact_settings_context(self):
        self.assertEqual((True, (900,150)), RECORDER.main_switch(self.page()))
        self.assertEqual((False, (900,150)), RECORDER.main_switch(self.page("false")))

    def test_unrelated_app_channel_or_settings_surface_is_not_tapped(self):
        for page in (self.page().replace('Lembretes','Outro canal'), self.page().replace('O.R.I.O.N.','Other app'),
                     self.page().replace('package="com.android.settings"','package="untrusted.app"')):
            with self.assertRaises(ValueError): RECORDER.main_switch(page)

    def test_ambiguous_or_missing_main_switch_is_not_guessed(self):
        with self.assertRaises(ValueError): RECORDER.main_switch(self.page(extra='<node checkable="true" checked="false" bounds="[1,1][2,2]"/>'))
        with self.assertRaises(ValueError): RECORDER.main_switch(self.page().replace('switch_bar','unrelated_switch'))

    def test_invalid_control_state_and_bounds_are_rejected(self):
        for checked, bounds in [('unknown','[1,1][2,2]'),('true','[4,4][1,1]'),('true','[0,0][99999,99999]'),('true','arbitrary')]:
            with self.assertRaises(ValueError): RECORDER.main_switch(self.page(checked,bounds))

    def test_settings_intent_is_restricted_to_system_package_and_exact_owned_channel(self):
        command=RECORDER.settings_command()
        self.assertEqual('com.android.settings',command[command.index('-p')+1])
        self.assertEqual('orion_reminders',command[-1])
        self.assertIn('android.settings.CHANNEL_NOTIFICATION_SETTINGS',command)


if __name__ == '__main__': unittest.main()
