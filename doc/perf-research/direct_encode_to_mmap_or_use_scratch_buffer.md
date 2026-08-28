# Direct write to MMAP or via buffer

Tests need to be made, as code can adapt, both are memory segments and all code writes from offset.

So far direct write to MMAP wins. 

- object encoding writes placeholder "V2BIG" when JSON does not fit
- 16 MB buffer is configurable
- if there are message queued, write multiple events and flush when we exceed 


