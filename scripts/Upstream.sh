#!/usr/bin/env bash

# requires curl & jq
# Credit: https://github.com/MenthaMC/Mint
#
# Usage:
# upstreamCommit --luminol HASH [--luminol-branch BRANCH]
#
# flags:
#   --luminol HASH           the commit hash to use for comparing commits between LuminolMC/Luminol (compare HASH...TARGET)
#   --luminol-branch BRANCH  (Optional) target branch or ref for Luminol when new luminolRef not present. Default: main

function getCommits() {
    curl -s -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/$1/compare/$2...$3" \
        | jq -r '.commits[] | "'"$1"'@\(.sha[:8]) \(.commit.message | split("\r\n")[0] | split("\n")[0])" | sub("\\[ci( |-)skip]"; "[ci/skip]")'
}

(
set -e
PS1="$"

# default for target branch/ref
luminolBranch="main"

# try to read the new luminolRef from git diff (if present)
newLuminolRef=$(git diff gradle.properties | awk '/^\+[[:space:]]*luminolRef[[:space:]]*=/ {print $NF}')

# arg-hash variable (set by --luminol)
luminolHashArg=""

# parse args
while true; do
    case "$1" in
        --luminol)
            luminolHashArg="$2"
            shift 2
            ;;
        --luminol-branch)
            luminolBranch="$2"
            shift 2
            ;;
        "" )
            break
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

if [ -z "$luminolHashArg" ]; then
    echo "No luminol hash provided. Usage: upstreamCommit --luminol HASH [--luminol-branch BRANCH]"
    exit 1
fi

# decide target ref: prefer the new luminolRef in gradle.properties if present, otherwise use luminolBranch
if [ -n "$newLuminolRef" ]; then
    targetRef="$newLuminolRef"
else
    targetRef="$luminolBranch"
fi

# get commits
luminolCommits=$(getCommits "LuminolMC/Luminol" "$luminolHashArg" "$targetRef")

if [ -z "$luminolCommits" ]; then
    echo "No Luminol updates detected between $luminolHashArg and $targetRef."
    exit 0
fi

logsuffix="\n\nLuminol Changes (LuminolMC/Luminol $luminolHashArg...$targetRef):\n$luminolCommits"
disclaimer="Upstream has released updates that appear to apply and compile correctly"
log="Updated Upstream (Luminol)\n\n${disclaimer}${logsuffix}"

git add gradle.properties
echo -e "$log" | git commit -F -

) || exit 1