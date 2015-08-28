#!/bin/bash -x

devs=('port' 'chain' 'router' 'bridge')
for dev in "${devs[@]}"
do
    ids=`midonet-cli -A -e list $dev | cut -d ' ' -f 2`
    for id in $ids
    do
        `midonet-cli -A -e delete $dev $id`
    done
done

