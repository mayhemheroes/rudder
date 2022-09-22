# Build Stage
FROM ghcr.io/evanrichter/cargo-fuzz:latest as builder

RUN apt update && apt install libpq-dev

## Add source code to the build stage.
ADD . /src
WORKDIR /src

RUN echo building instrumented harnesses && \
    bash -c "pushd relay/sources/relayd/fuzz && cargo +nightly -Z sparse-registry fuzz build && popd" && \
    mv relay/sources/relayd/fuzz/target/x86_64-unknown-linux-gnu/release/runinfo /runinfo && \
    echo done

RUN echo building non-instrumented harnesses && \
    export RUSTFLAGS="--cfg fuzzing -Clink-dead-code -Cdebug-assertions -C codegen-units=1" && \
    bash -c "pushd relay/sources/relayd/fuzz && cargo +nightly -Z sparse-registry build --release && popd" && \
    mv relay/sources/relayd/fuzz/target/release/runinfo /runinfo_no_inst && \
    echo done

# Package Stage
FROM rustlang/rust:nightly

COPY --from=builder /runinfo /runinfo_no_inst /
COPY --from=builder \
            /usr/lib/x86_64-linux-gnu/libpq.so.5 \
            /usr/lib/x86_64-linux-gnu/libssl.so.1.1 \
            /usr/lib/x86_64-linux-gnu/libcrypto.so.1.1 \
            /usr/lib/x86_64-linux-gnu/libgssapi_krb5.so.2 \
            /usr/lib/x86_64-linux-gnu/libldap_r-2.4.so.2 \
            /usr/lib/x86_64-linux-gnu/libkrb5.so.3 \
            /usr/lib/x86_64-linux-gnu/libk5crypto.so.3 \
            /lib/x86_64-linux-gnu/libcom_err.so.2 \
            /usr/lib/x86_64-linux-gnu/libkrb5support.so.0 \
            /lib/x86_64-linux-gnu/libkeyutils.so.1 \
            /lib/x86_64-linux-gnu/libresolv.so.2 \
            /usr/lib/x86_64-linux-gnu/liblber-2.4.so.2 \
            /usr/lib/x86_64-linux-gnu/libsasl2.so.2 \
            /usr/lib/x86_64-linux-gnu/libgnutls.so.30 \
            /usr/lib/x86_64-linux-gnu/libp11-kit.so.0 \
            /usr/lib/x86_64-linux-gnu/libidn2.so.0 \
            /usr/lib/x86_64-linux-gnu/libunistring.so.2 \
            /usr/lib/x86_64-linux-gnu/libtasn1.so.6 \
            /usr/lib/x86_64-linux-gnu/libnettle.so.6 \
            /usr/lib/x86_64-linux-gnu/libhogweed.so.4 \
            /usr/lib/x86_64-linux-gnu/libgmp.so.10 \
            /usr/lib/x86_64-linux-gnu/libffi.so.6 \
            /usr/lib/x86_64-linux-gnu/