# Capacity record

At the initial resumed gate, available space was 331841519616 bytes. The earlier `>=334 GiB` threshold was coordinator
over-precision rather than a user or repository invariant. P123-C consumed about 23 GiB, while the audited minimum need
for P123-D was 12 GiB. This left more than 300 GiB projected margin.

After focused validation and before lifecycle work, available space was 331485790208 bytes. After lifecycle and exact
P123-C control lanes, available space was 329394102272 bytes. Each measurement remains far above the conservative
50 GiB safety floor. After the bounded final stress run, available space was 328986279936 bytes. No cleanup was
performed or authorized.
