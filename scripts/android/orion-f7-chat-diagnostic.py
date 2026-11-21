#!/usr/bin/env python3
"""Temporarily report only exception types and source locations from synthetic UI tests."""
import importlib.util
from pathlib import Path
import re

runner_path = Path(__file__).with_name('verify-chat-ui.py')
spec = importlib.util.spec_from_file_location('orion_chat_runner', runner_path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
parse_instrumentation = runner.parse_instrumentation


def diagnose(output, status):
    for line in output.splitlines():
        if 'could not find any node' in line:
            print('Draft assertion found no node', flush=True)
        if 'EditableText = ' in line:
            value = line.split('EditableText = ', 1)[1].strip()
            category = ('previous' if 'criar tarefa: comprar pão' in value else
                'empty' if value in {"''", '""'} else
                'other-non-empty')
            print(f'Draft semantics category: {category}', flush=True)
        exception = re.fullmatch(r'INSTRUMENTATION_STATUS: stack=([\w.]+(?:Error|Exception))(?::.*)?', line)
        if exception:
            print(f'Test exception type: {exception[1]}', flush=True)
        source = re.fullmatch(
            r'\s*at network\.orion\.app\.chat\.[\w.$<>]+\((OrionChat(?:Input)?ScreenTest\.kt:[0-9]{1,4})\)', line)
        if source:
            print(f'Test failure source: {source[1]}', flush=True)
    return parse_instrumentation(output, status)


runner.parse_instrumentation = diagnose
raise SystemExit(runner.main())
