Universal Resource Allocator for Cisco NSO
==========================================
This is a Cisco NSO (formerly Tail-f NCS) package for automatically allocating
resources.

When provisioning networks one often finds the need to allocate some form of
resource, be it VLANs, IP addresses, interface IDs, tunnel keys or something
else. URA sets out to alleviate this task by automatically allocating resources
from pools inside of NCS.

URA is implemented using a CDB subscriber and is currently only able to
allocate integers but this is the most common type of request. For example,
allocating a VLAN ID is really just about allocating an integer in a range of 1
through 4095.

Installation
------------
Source ncsrc and run make to build the package:
```
  source $NCS_DIR/ncsrc
  make
```

Then create the symlink into your ncs-run directory:
```
  cd $NCS_RUN/packages
  ln -s $PATH_TO_URA
```

Usage
-----
First define an integer range in NSO:
```
services {
    ura {
        integer MY-RANGE {
            min-value 0;
            max-value 1337;
        }
    }
}
```
This has defined an integer range that is called "MY-RANGE" and that will hand
out values from 0 to 1337 (inclusive) starting from 0.

To request a value, you write into the "request" list under the range;
```
services {
    ura {
        integer MY-RANGE {
            min-value 0;
            max-value 1337;
            request MY-FIRST-REQUEST {
            }
        }
    }
}
```
Here we created our first request that is aptly named "MY-FIRST-REQUEST". Once
you commit this to CDB config (the normal CDB instance that holds your
configuration), the CDB subscriber will be triggered and allocate a value to
your request. The allocated value will be written to CDB oper, so you cannot
read it by looking at the config, in fact, there is no command at all to read
it from the CLI. To read the value you need to construct a service that can
open up a CDB oper session and read the value.

For demonstration and testing purposes URA is shipped with a service called
ura-test that can request an integer and log the output.
```
services {
    ura-test MY-TEST {
        integer-range MY-RANGE;
    }
}
```
This will create a ura-test instance that requests a number from the integer
range "MY-RANGE", which we had previously defined. When you commit this you can
follow the progress in the Java log. You should see something along the lines
of the following (I've numbered the lines for easy reference):
```
1 <INFO> 27-Jun-2015::14:23:12.045 uraTest Did-70-Worker-30: - uraTest create() called
2 <INFO> 27-Jun-2015::14:23:12.060 uraTest Did-70-Worker-30: - /ncs:services/ura-test:ura-test{MY-TEST} CDB: null
3 <INFO> 27-Jun-2015::14:23:12.087 NavuCdbSub (ura:Reactive FM CDB Config Subscriber)-Run-2: - Number of requests: 1
4 <INFO> 27-Jun-2015::14:23:12.088 NavuCdbSub (ura:Reactive FM CDB Config Subscriber)-Run-2: - Requested URA action {MY-TEST_0}, op=ALLOCATE , type=Integer , from={MY-RANGE}
5 <INFO> 27-Jun-2015::14:23:12.099 NavuCdbSub (ura:Reactive FM CDB Config Subscriber)-Run-2: - Pool: MY-RANGE min: 0 max: 1337 allocMethod: 0
6 <INFO> 27-Jun-2015::14:23:13.104 NavuCdbSub (ura:Reactive FM CDB Config Subscriber)-Run-2: - SET: /ncs:services/ura:ura/integer{MY-RANGE}/request{MY-TEST_0}/integer -> 0
7 <INFO> 27-Jun-2015::14:23:13.137 uraTest Did-70-Worker-30: - uraTest create() called
8 <INFO> 27-Jun-2015::14:23:13.143 uraTest Did-70-Worker-30: - /ncs:services/ura-test:ura-test{MY-TEST} CDB: 0
```
We can see that the uraTest() create callback is called on line 1. On line 2 we
see that uraTest read the current value from CDB oper. It is null which means
that we haven't received an allocation yet, which isn't very surprising. It
then creates a request by writing the following to CDB conf:
```
ura {
    integer MY-RANGE {
        request MY-TEST_0 {
            redeploy-service "/ncs:services/ura-test:ura-test{MY-TEST}";
        }
    }
}
```
It requests an integer from MY-RANGE, just like we told it too, and also asks
that URA should redeploy ura-test instance "MY-TEST" once it has finished
allocating integers.

On line 3 and 4 we can see that the CDB subscriber of URA has received a
request and it prints a bit of information about the request. On line 5 we see
that it runs its algorithm to find an available integer and on line 6 it writes
it to CDB oper and then redeploys the uraTest instance.

On line 7 and 8, we once again see the uraTest create() callback being called,
just like on the first and second line but this time when uraTest reads the
value from CDB oper we see that we have been allocated an integer - 0!

Look at the source code of uraTest to understand how it achieves this in detail
and implement something similar for your code where you need integer
allocations!


Configuration options
---------------------
 * allocation-method - which method to use to allocate integers
  - first - this will start searching from the lowest number and return the
    first one that is not in use. If a request is deleted the next request will
    reuse that number which means the list of allocated numbers will be kept as
    compact as possible.
  - max - this will look at the currently allocated integers, find the maximum
    value and then try to return the next value (i.e. max+1). If this fails
    (due to the current maximum value being equal to max-value) it will revert
    to using the "first" method. "max" is good to use if you know you will not
    deallocate resources (which results in holes) or if you do not care about
    holes in your range.
 * allocation-order - this specifies if integers should be allocated in
   ascending or descending, i.e. starting from min-value or from max-value
   respectively
 * min-value - the minimum value (inclusive)
 * max-value - the maximum value (inclusive)


TODO
----
min-value and max-value are not honored in either allocation-method and
allocation-method max doesn't fall back to "first" like it should.

allocation-order is not implemented at all. integers are allocated in
increasing order for now.

Please see GitHub issues for more details or whatever.
