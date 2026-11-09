#!/bin/sh
set -eu
: "${ANDROID_HOME:?Android SDK is required}"
test "$#" -eq 1
apk=$1
test -f "$apk"
scratch=$(mktemp -d)
trap 'rm -r -- "$scratch"' EXIT HUP INT TERM
unzip -Z1 "$apk" | awk '/^classes([0-9]+)?\.dex$/' > "$scratch/entries"
test -s "$scratch/entries"
while IFS= read -r entry; do
    unzip -p "$apk" "$entry" > "$scratch/classes.dex"
    "$ANDROID_HOME/build-tools/36.0.0/dexdump" "$scratch/classes.dex" >> "$scratch/dexdump"
done < "$scratch/entries"
perl - "$scratch/dexdump" <<'PERL'
use strict;
use warnings;
my $engine = 'Lnetwork/orion/ai/llama/OrionLlamaEngine;';
my $callbacks = 'Lnetwork/orion/ai/llama/OrionNativeCallbacks;';
my %expected = (
    "$engine/nativeLoad" => "(IIILnetwork/orion/ai/llama/OrionNativeCallbacks;)J",
    "$engine/nativeInfer" => "(J[BIFLnetwork/orion/ai/llama/OrionNativeCallbacks;)V",
    "$engine/nativeDestroy" => '(J)V',
    "$callbacks/shouldCancel" => '()Z',
    "$callbacks/emit" => '([B)Z',
);
my (%seen, $class, $name, $type);
while (<>) {
    if (/Class descriptor\s*:\s*'([^']+)'/) { $class = $1; $name = ''; $type = ''; }
    next unless defined($class) && ($class eq $engine || $class eq $callbacks);
    if (/^\s+name\s*:\s*'([^']+)'/) { $name = $1; $type = ''; }
    if (/^\s+type\s*:\s*'([^']+)'/) { $type = $1; }
    if (/^\s+access\s*:\s*(.*)/ && defined($name) && exists($expected{"$class/$name"})) {
        my $access = $1;
        die "JNI descriptor mismatch\n" unless $type eq $expected{"$class/$name"};
        die "JNI instance method became STATIC\n" if $access =~ /\bSTATIC\b/;
        die "JNI native method lost NATIVE access\n" if $class eq $engine && $access !~ /\bNATIVE\b/;
        die "JNI callback lost PUBLIC access\n" if $class eq $callbacks && $access !~ /\bPUBLIC\b/;
        $seen{"$class/$name"} = 1;
    }
}
for my $method (sort keys %expected) { die "JNI method missing: $method\n" unless $seen{$method}; }
print "Minified DEX JNI class/method/signature/access contract: PASS\n";
PERL
