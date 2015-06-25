Universal Resource Allocator for Cisco NSO
==========================================
This is a Cisco NSO (formerly Tail-f NCS) package for automatically allocating
resources.

When provisioning networks one often finds the need to allocate some form of
resource, be it VLANs, IP addresses, interface IDs, tunnel keys or something
else. URA sets out to alleviate this task by automatically allocating resources
from pools inside of NCS.

URA is implemented using a CDB subscriber and is currently only able to
allocate integers. Assigning a VLAN ID is really just about assigning an
integer in a range of 1 through 4095.

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
