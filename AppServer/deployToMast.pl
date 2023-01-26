#!/usr/bin/perl --
use strict;
use warnings;
use feature qw(say);
use Getopt::Long;

my $verbose =0;
GetOptions(
    "verbose|v:+" => \$verbose,
    ) or die "bad options.";

# systemやcloseの結果を整形する
sub cmdResult($){
    my($rv)=@_;
    if( $rv == 0 ){
        return;
    }elsif( $rv == -1 ){
        return "failed to execute: $!";
    }elsif ( $rv & 127 ) {
        return sprintf "signal %d", ($rv & 127);
    }else {
        return sprintf "exitCode %d",($rv>>8);
    }
}

sub cmd($){
    system $_[0];
    my $error = cmdResult $?;
    $error and die "$error cmd=$_[0]";
}

sub chdirOrThrow($){
    my($dir)=@_;
    chdir($dir) or die "chdir failed. $dir $!";
}

sub cmdRead($&){
    my($cmd,$block)=@_;

    open(my $fh, "-|", "$cmd 2>&1") or die "can't fork. $!";

    local $_;

    while(1){
        $_ = <$fh>;
        last if not defined $_;
        $block->();
    }

    if(not close($fh) ){
        my $e1 = $!;
        my $e2 = cmdResult $?;
        die "execute failed. $e1 $e2";
    }
}

cmd qq(./gradlew shadowJar);

my $jarSrc = `ls -1t build/libs/*-all.jar|head -n 1`;
$jarSrc =~ s/\s+\z//;
(-f $jarSrc) or die "missing jarSrc [$jarSrc]";

cmd qq(scp $jarSrc mast:/m/subwaytooter-app-server/appServer2/appServer.jar);

