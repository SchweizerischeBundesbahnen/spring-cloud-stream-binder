#!/bin/bash

# --- Configuration ---
SOURCE_BRANCH="master"
DESTINATION_BRANCH="maintenance/5.x.x"
# --- End of Configuration ---

set -e # Exit immediately if a command exits with a non-zero status.

# 1. Checkout and update the maintenance branch
echo "Switching to branch '$DESTINATION_BRANCH'..."
# This also serves as a check for a clean working directory. It will fail if there are uncommitted changes.
if ! git checkout "$DESTINATION_BRANCH"; then
    echo "Error: Could not check out branch '$DESTINATION_BRANCH'. Please ensure it exists and your working directory is clean."
    exit 1
fi

echo "Fetching the latest changes from the remote..."
git fetch --all --prune

# 3. Create a temporary directory and save the current POM files
TEMP_POM_DIR=$(mktemp -d)
echo "Saving current pom.xml files to a temporary location: $TEMP_POM_DIR"
find . -name "pom.xml" -exec cp --parents {} "$TEMP_POM_DIR/" \;

# 4. Perform the hard reset
echo "Resetting '$DESTINATION_BRANCH' to match 'origin/$SOURCE_BRANCH'..."
git reset --hard "origin/$SOURCE_BRANCH"

# 5. Restore the saved POM files
echo "Restoring the saved pom.xml files..."
# The `cp` command copies the contents of the temporary directory back into the current directory.
# This works because we preserved the file structure with --parents earlier.
cp -rT "$TEMP_POM_DIR/" .
rm -rf "$TEMP_POM_DIR" # Clean up temporary directory