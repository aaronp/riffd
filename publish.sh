#!/usr/bin/env bash

source ./makeDoc.sh

rm -rf api
sbt "project riffJS" fullOptJS doc

# checkout to the gh-pages branch
git checkout gh-pages

# pull the latest updates
git pull origin gh-pages --rebase

# copy the static site files into the current directory.
cp -R src/gitbook/_book/* .
#mv src/gitbook/demo .
mkdir api
cp -R ./js/target/scala-2.13/api* api/

# remove 'node_modules' and '_book' directory
git clean -fx node_modules
git clean -fx src/gitbook/node_modules
git clean -fx src/gitbook/_book

#git add .
#git commit -am "Update docs"
#git push origin gh-pages
#git checkout -