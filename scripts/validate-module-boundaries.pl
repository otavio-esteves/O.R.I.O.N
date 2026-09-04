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
my %dependencies;
for my $module (@modules) {
    (my $directory = $module) =~ s/^://;
    $directory =~ s/:/\//g;
    my $build_file = "$directory/build.gradle.kts";
    open my $build_handle, '<', $build_file
        or die "$module: missing $build_file\n";
    my $build = <$build_handle>;
    my @project_dependencies = $build =~ /project\(\s*"(:[^"]+)"\s*\)/g;
    for my $dependency (@project_dependencies) {
        die "$module: dependency on unknown module $dependency\n"
            unless $modules{$dependency};
        die "$module: core modules must not depend on app\n"
            if $module =~ /^:core:/ && $dependency eq ':app';
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

print "module boundaries valid: " . join(', ', sort @modules) . "\n";
