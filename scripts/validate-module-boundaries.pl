#!/usr/bin/env perl
use strict;
use warnings;

open my $settings_handle, '<', 'settings.gradle.kts'
    or die "cannot open settings.gradle.kts: $!\n";
local $/;
my $settings = <$settings_handle>;
my @modules = $settings =~ /include\(\s*"(:[^"]+)"\s*\)/g;
die "no Gradle modules declared\n" unless @modules;

my %modules = map { $_ => 1 } @modules;
# Master Implementation Plan V1.2 section 7.1: required initial module inventory.
my @required = qw(
    :app :core:common :core:model :core:commands :core:queries
    :core:events :core:ipc :core:orchestrator :core:policy :core:actions
    :core:resources :core:time :core:recovery :core:health :core:security
    :core:observability :core:ingress :core:exitinfo :core:skills:metadata :data:database
    :data:datastore :data:repository :data:tasks :data:reminders :data:actions
    :data:events :data:memory :ai:api :voice:api :voice:ipc
    :skills:api :scheduler :feature:home :feature:diagnostics :feature:settings
);
for my $required (@required) {
    die "missing required module $required\n" unless $modules{$required};
}

my %dependencies;
for my $module (@modules) {
    (my $directory = $module) =~ s/^://;
    $directory =~ s/:/\//g;
    my $build_file = "$directory/build.gradle.kts";
    open my $build_handle, '<', $build_file
        or die "$module: missing $build_file\n";
    my $build = <$build_handle>;
    my @project_dependencies = $build =~ /project\(\s*(?:path\s*=\s*)?"(:[^"]+)"\s*\)/g;
    for my $dependency (@project_dependencies) {
        die "$module: dependency on unknown module $dependency\n"
            unless $modules{$dependency};
        die "$module: core modules must not depend on app\n"
            if $module =~ /^:core:/ && $dependency eq ':app';
        die "$module: forbidden dependency on $dependency; use Core APIs/use cases\n"
            if $module =~ /^:feature:/ && $dependency =~ /^:data:/;
    }
    $dependencies{$module} = \@project_dependencies;
}

my (%visiting, %visited);
sub visit {
    my ($module) = @_;
    die "$module: Gradle project dependency cycle detected\n" if $visiting{$module};
    return if $visited{$module};
    $visiting{$module} = 1;
    visit($_) for @{$dependencies{$module}};
    delete $visiting{$module};
    $visited{$module} = 1;
}
visit($_) for @modules;

# Check the full reachable graph so an intermediary cannot hide a violation.
sub forbidden {
    my ($source, $target) = @_;
    return 1 if $source =~ /^:(?:ai|voice):/ && $target =~ /^:data:/;
    return 1 if $source eq ':core:policy' && $target =~ /^:ai:/ && $target ne ':ai:api';
    return 1 if $source =~ /^:data:/ && ($target =~ /^:feature:/ || $target eq ':app');
    return 1 if $source =~ /^:core:/ && $target eq ':app';
    return 0;
}
for my $source (@modules) {
    my %seen;
    my @pending = @{$dependencies{$source}};
    while (my $target = shift @pending) {
        next if $seen{$target}++;
        die "$source: forbidden dependency on $target (direct or transitive)\n"
            if forbidden($source, $target);
        push @pending, @{$dependencies{$target}};
    }
}

print "module boundaries valid: " . join(', ', sort @modules) . "\n";
