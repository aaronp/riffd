#!/usr/bin/env bash

source ./makeDoc.sh

# checkout to the gh-pages branch
git checkout gh-pages

# pull the latest updates
git pull origin gh-pages --rebase

# copy the static site files into the current directory.
cp -R src/gitbook/_book/* .
mv src/gitbook/demo .

# remove 'node_modules' and '_book' directory
git clean -fx node_modules
git clean -fx src/gitbook/node_modules
git clean -fx src/gitbook/_book

# add all files
git add .

# commit
#git commit -am "Update docs"

# push to the origin
#git push origin gh-pages

# checkout to the master branch
#git checkout master