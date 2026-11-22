#!/usr/bin/env python3
"""Generate the fixed F11 speech smoke corpus with local eSpeak NG 1.52.0."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import wave


SAMPLE_RATE_HZ = 16_000
CORPUS = {
    'enroll': [
        ('Orion', 'm1', 155, 48), ('Orion', 'f1', 145, 52), ('Orion', 'm3', 170, 45),
    ],
    'positive': [
        ('Orion', 'm2', 150, 43), ('Orion', 'm4', 165, 52), ('Orion', 'm5', 175, 58),
        ('Orion', 'f2', 148, 38), ('Orion', 'f3', 152, 48), ('Orion', 'f4', 162, 55),
        ('Orion', 'croak', 140, 46), ('Orion', 'whisper', 155, 49),
    ],
    'negative': [
        ('oriundo', 'm2', 170, 55), ('olho', 'f2', 158, 49), ('ouvir', 'm4', 161, 50),
        ('outra', 'f3', 158, 46), ('óleo', 'm5', 156, 57), ('Aurélio', 'f4', 152, 52),
        ('Aurora', 'm2', 148, 47), ('arame', 'f2', 175, 51), ('coruja', 'm4', 159, 53),
        ('agora', 'f4', 149, 46), ('bateria', 'm5', 148, 51), ('acorda', 'f3', 168, 57),
    ],
}


def convert_pcm(source_path, output_path):
    with wave.open(str(source_path), 'rb') as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError('Expected mono PCM16 output from eSpeak NG.')
        source_rate = source.getframerate()
        pcm = tuple(value for (value,) in struct.iter_unpack('<h', source.readframes(source.getnframes())))
    cutoff = max(200, max(abs(sample) for sample in pcm) // 40)
    speech = [index for index, sample in enumerate(pcm) if abs(sample) >= cutoff]
    if not speech:
        raise ValueError('Expected an audible synthetic utterance.')
    padding = source_rate // 100
    pcm = pcm[max(0, speech[0] - padding):speech[-1] + padding]
    samples = []
    for position in range((len(pcm) - 1) * SAMPLE_RATE_HZ // source_rate):
        left, fraction = divmod(position * source_rate, SAMPLE_RATE_HZ)
        interpolated = (pcm[left] * (SAMPLE_RATE_HZ - fraction) + pcm[left + 1] * fraction) / SAMPLE_RATE_HZ
        samples.append(round(interpolated))
    if not 3_200 <= len(samples) <= 25_600:
        raise ValueError('Synthetic sample is outside the F11 detector bounds.')
    with wave.open(str(output_path), 'wb') as output:
        output.setparams((1, 2, SAMPLE_RATE_HZ, len(samples), 'NONE', 'not compressed'))
        output.writeframes(struct.pack(f'<{len(samples)}h', *samples))


def generate(command, destination):
    if destination.exists() or destination.is_symlink():
        raise ValueError('The corpus directory must be new.')
    with tempfile.TemporaryDirectory(prefix='.orion-wake-word-', dir=destination.parent) as staging:
        root = Path(staging) / 'corpus'
        root.mkdir()
        source = Path(staging) / 'source.wav'
        for group, utterances in CORPUS.items():
            directory = root / group
            directory.mkdir()
            for index, (text, voice, speed, pitch) in enumerate(utterances, start=1):
                subprocess.run([*command, '-D', '-v', f'pt-br+{voice}', '-s', str(speed), '-p', str(pitch),
                                '-w', str(source), text], check=True)
                convert_pcm(source, directory / f'{index:02}.wav')
        if destination.exists() or destination.is_symlink():
            raise ValueError('The corpus directory must be new.')
        root.rename(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--espeak', default='espeak-ng')
    parser.add_argument('--data-path', type=Path, help='Parent directory of espeak-ng-data, if needed')
    args = parser.parse_args()
    command = [args.espeak]
    if args.data_path:
        command.append(f'--path={args.data_path}')
    result = subprocess.run([*command, '--version'], capture_output=True, text=True, check=True)
    if not re.match(r'eSpeak NG text-to-speech: 1\.52\.0\b', result.stdout):
        parser.error('Use eSpeak NG version 1.52.0 to reproduce the recorded corpus.')
    generate(command, args.destination)


if __name__ == '__main__':
    main()
