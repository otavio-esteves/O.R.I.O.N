#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Path qw(make_path);
use Cwd qw(abs_path getcwd);

my $root = getcwd();
my $validator = abs_path('scripts/validate-module-boundaries.pl');
my @modules = qw(app core:common core:model core:commands core:queries core:events
    core:ipc core:orchestrator core:policy core:actions core:resources core:time
    core:recovery core:health core:security core:observability core:ingress
    core:exitinfo core:skills:metadata data:database data:datastore data:repository
    data:tasks data:reminders data:actions data:events data:memory ai:api voice:api
    voice:ipc skills:api scheduler feature:home feature:diagnostics feature:settings);
sub write_file {
    my ($path, $text) = @_;
    open my $file, '>', $path or die "$path: $!";
    print {$file} $text;
    close $file or die "$path: $!";
}
sub scenario {
    my ($name, $change, $expected) = @_;
    my $dir = tempdir(CLEANUP => 1);
    chdir $dir or die $!;
    write_file('settings.gradle.kts', join('', map { "include(\":$_\")\n" } @modules));
    for (@modules, 'ai:service') {
        (my $path = $_) =~ s/:/\//g;
        make_path($path);
        write_file("$path/build.gradle.kts", '');
    }
    $change->();
    my $output = qx{$^X "$validator" 2>&1};
    my $status = $?;
    if (defined $expected) {
        isnt($status, 0, "$name rejected");
        like($output, $expected, "$name diagnostic");
    } else {
        is($status, 0, "$name accepted") or diag($output);
    }
    chdir $root or die $!;
}
sub edge {
    my ($from, $to) = @_;
    $from =~ s/:/\//g;
    write_file("$from/build.gradle.kts", "dependencies { implementation(project(\":$to\")) }\n");
}
scenario('empty foundation', sub {}, undef);
scenario('permitted API dependencies', sub {
    edge('app', 'core:common'); edge('voice:ipc', 'core:ipc');
    edge('feature:home', 'core:queries'); edge('data:repository', 'data:database');
}, undef);
scenario('missing required module', sub {
    write_file('settings.gradle.kts', "include(\":app\")\n");
}, qr/missing required module/);
scenario('missing build file', sub { unlink 'core/model/build.gradle.kts' or die $! }, qr/missing/);
scenario('unknown dependency', sub { edge('app', 'missing') }, qr/unknown module/);
scenario('core to app', sub { edge('core:common', 'app') }, qr/must not depend on app/);
scenario('cycle', sub { edge('core:model', 'core:common'); edge('core:common', 'core:model') }, qr/cycle/);
for my $from ('ai:api', 'voice:api', 'voice:ipc') {
    scenario("$from to database", sub { edge($from, 'data:database') }, qr/forbidden dependency/);
    scenario("$from to repository", sub { edge($from, 'data:repository') }, qr/forbidden dependency/);
}
scenario('transitive voice to database', sub {
    edge('voice:api', 'core:ipc'); edge('core:ipc', 'data:database');
}, qr/forbidden dependency/);
scenario('policy to AI implementation', sub {
    open my $settings, '>>', 'settings.gradle.kts' or die $!;
    print {$settings} "include(\":ai:service\")\n"; close $settings;
    edge('core:policy', 'ai:service');
}, qr/forbidden dependency/);
scenario('policy to AI API', sub { edge('core:policy', 'ai:api') }, undef);
scenario('repository to UI', sub { edge('data:repository', 'feature:home') }, qr/forbidden dependency/);
scenario('feature to data implementation', sub { edge('feature:home', 'data:tasks') }, qr/forbidden dependency/);
scenario('feature through Core use case', sub {
    edge('feature:home', 'core:commands'); edge('core:commands', 'data:repository');
}, undef);
scenario('named project path', sub {
    write_file('voice/api/build.gradle.kts', 'implementation(project(path = ":data:database"))');
}, qr/forbidden dependency/);
done_testing();
