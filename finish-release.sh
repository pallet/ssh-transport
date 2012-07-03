#!/bin/bash

# finish the release after updating release notes


if [[ $# -lt 1 ]]; then
  echo "usage: $(basename $0) new-version" >&2
  exit 1
fi

version=$1

echo "finish release of $version"

echo -n "Commiting readme and release notes.  Enter to continue:" && read x \
&& git add -u README.md ReleaseNotes.md \
&& git commit -m "Update readme and release notes for $version" \
&& echo -n "Peform release.  Enter to continue:" && read x \
&& mvn release:clean \
&& mvn release:prepare \
&& mvn release:perform \
&& git flow release finish -n $version \
&& mvn nexus:staging-close \
&& mvn nexus:staging-promote

mvn site

echo "=========================="
echo "Now push to github"
echo "         to gh-pages"
