I used claude code (Opus 5.5).
It was used to help generate much of the api and files based on the pdf documentation.
I found that I had to make sure it kept it all as light and simple as it made decisions that kept pushing the code base to be larger than needed.
Also, it assisted me with GitHub actions as I am more familiar with Jenkins (but i don't have time to set it up).
It helps speed up the generic coding, but unfortunate I don't have time to properly review this code (i would never send merge code that I haven't properly review).

Where it was wrong, and what I changed:
- It suggested a local VirtualBox VM; on my machine it was unstable, so I moved to a GCP VM.
- Its deploy scripts had bugs I only found by testing on the VM: a pipe that broke runner registration, and
  a missing permission for setting the VM label.
- Its rollback kept only "the previous tag", so after an automatic rollback a manual one would have
  redeployed the broken build. I had it write a rollback drill, which exposed this, and it was fixed.
- It labelled the VM with the commit SHA; I changed it to the version tag.
