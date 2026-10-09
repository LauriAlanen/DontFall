#!/usr/bin/env bash
# DontFall container entrypoint.
#
# When run from the workspace root (the compose service's working_dir),
# load the bitbake environment first so commands run "inside" envsetup.sh
# without a manual `source`. Its banner goes to stderr to keep stdout
# clean for one-shot invocations.
if [ -f ./envsetup.sh ]; then
    # envsetup.sh takes an optional build dir as $1 - hide our command's
    # args from it so it always uses the default build directory.
    _df_args=("$@")
    set --
    source ./envsetup.sh >&2 || exit 1
    set -- "${_df_args[@]}"
fi
exec "$@"
