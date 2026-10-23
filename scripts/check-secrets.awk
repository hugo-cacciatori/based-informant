# Scanner used by scripts/check-secrets. Reads text on stdin and prints one line per finding,
# "<where>: <kind>", never the secret itself.
#
# Variables (awk -v):
#   mode  "lines" (default): stdin is the content of one file, named by `path`
#         "diff": stdin is `git log -p --unified=0` output; only added lines are scanned
#   path  the file being scanned (lines mode)
#
# The patterns avoid {n,} intervals so that any POSIX awk can run them.

function rep(s, n,    r, i) { r = ""; for (i = 0; i < n; i++) r = r s; return r }

# Real keys mix digits, upper and lower case; placeholders and identifiers usually don't.
function mixed(s) { return s ~ /[0-9]/ && s ~ /[A-Z]/ && s ~ /[a-z]/ }

function report(where, kind) { print where ": " kind }

function scan(text, where, isprops,    s, c, v) {
    s = text
    while (match(s, RE_RFAB)) {
        c = substr(s, RSTART, RLENGTH)
        if (!(c in ALLOW) && mixed(substr(c, 6))) { report(where, "possible rfab API key"); break }
        s = substr(s, RSTART + RLENGTH)
    }
    s = text
    while (match(s, RE_APIKEY)) {
        c = substr(s, RSTART, RLENGTH)
        v = c
        sub(RE_APIKEY_NAME, "", v)
        if (!(v in ALLOW) && mixed(v)) { report(where, "X-API-Key header with a real-looking value"); break }
        s = substr(s, RSTART + RLENGTH)
    }
    if (text ~ RE_PRIVATE) report(where, "private key block")
    if (text ~ RE_GOOGLE) report(where, "possible Google OAuth client secret")
    if (text ~ RE_GITHUB) report(where, "possible GitHub token")
    if (text ~ RE_STOREPW) report(where, "keystore password written in the code")
    if (isprops && text ~ RE_PROPPW) report(where, "keystore password in a properties file")
}

BEGIN {
    B64 = "[A-Za-z0-9_-]"
    RE_RFAB = "rfab_" rep(B64, 20) B64 "*"
    RE_APIKEY_NAME = "^[Xx]-[Aa][Pp][Ii]-[Kk][Ee][Yy][\"' ]*[:=,][\"' ]*"
    RE_APIKEY = substr(RE_APIKEY_NAME, 2) rep("[A-Za-z0-9_.-]", 16) "[A-Za-z0-9_.-]*"
    RE_PRIVATE = "-----" "BEGIN ([A-Z]+ )*PRIVATE KEY-----"
    RE_GOOGLE = "GOCSPX-" rep(B64, 20)
    RE_GITHUB = "(gh[pousr]_" rep("[A-Za-z0-9]", 30) "|github_pat_" rep("[A-Za-z0-9_]", 30) ")"
    RE_STOREPW = "(storePassword|keyPassword)[ \t]*[=:(]?[ \t]*\"" rep("[^\"$]", 4) "[^\"$]*\""
    RE_PROPPW = "^[ \t]*(storePassword|keyPassword|storePass|keyPass)[ \t]*[=:][ \t]*[^ \t]"
    # Documented placeholders.
    ALLOW["rfab_your_key_here"] = 1
}

mode != "diff" { scan($0, path ":" NR, path ~ /\.properties$/); next }

# Diff mode.
/^@@COMMIT / { commit = $2; next }
/^diff --git / { inheader = 1; file = ""; next }
inheader && /^\+\+\+ / {
    file = substr($0, 5)
    if (file == "/dev/null") file = ""; else sub(/^b\//, "", file)
    next
}
/^@@ / {
    # @@ -a,b +c,d @@: c is the first added line number.
    inheader = 0
    n = $3; sub(/^\+/, "", n); sub(/,.*/, "", n); line = n + 0
    next
}
!inheader && /^\+/ {
    if (file != "") scan(substr($0, 2), file ":" line " (commit " commit ")", file ~ /\.properties$/)
    line++
    next
}
