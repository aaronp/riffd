#!/usr/bin/env bash

cd ./src/gitbook

echo "Serving preview out of `pwd` on port http://localhost:4000"

docker run --rm -d -v "$PWD:/gitbook" -p 4000:4000 billryan/gitbook gitbook serve

open "http://localhost:4000"