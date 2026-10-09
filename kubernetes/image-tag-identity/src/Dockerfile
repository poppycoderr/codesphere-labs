FROM busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e
ARG CONTENT
RUN echo "$CONTENT" > /version
CMD ["sh", "-c", "cat /version; sleep 3600"]
