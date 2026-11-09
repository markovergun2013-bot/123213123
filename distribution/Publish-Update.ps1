$ErrorActionPreference = 'Stop'
try {
    $root = $PSScriptRoot
    $apk = Join-Path $root 'Aura-Home.apk'
    $metadata = Get-Content -LiteralPath (Join-Path $root 'release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $hashAlgorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($apk)
    try { $hash = [BitConverter]::ToString($hashAlgorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() } finally { $stream.Dispose(); $hashAlgorithm.Dispose() }
    if ($hash -ne $metadata.sha256) { throw 'APK and release.json do not match. Use the files from the same build.' }
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (!$gh) {
        Write-Host 'GitHub CLI is required. Installing the official package with winget...'
        & winget.exe install --id GitHub.cli -e --source winget
        if ($LASTEXITCODE -ne 0) { throw 'Install GitHub CLI from https://cli.github.com and run this file again.' }
        $ghPath = Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe'
    } else { $ghPath = $gh.Source }
    if (!(Test-Path -LiteralPath $ghPath)) { throw 'GitHub CLI is not available. Reopen this script after installation.' }
    & $ghPath auth status --hostname github.com 2>$null
    if ($LASTEXITCODE -ne 0) {
        & $ghPath auth login --hostname github.com --git-protocol https --web
        if ($LASTEXITCODE -ne 0) { throw 'GitHub login did not complete.' }
    }
    $login = (& $ghPath api user --jq .login).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the signed-in account.' }
    if ($login -ne 'markovergun2013-bot') { throw 'Please sign in as markovergun2013-bot. This APK checks that account for updates.' }
    $repo = 'markovergun2013-bot/123213123'
    $repoInfo = & $ghPath repo view $repo --json visibility 2>$null
    if ($LASTEXITCODE -ne 0) {
        & $ghPath repo create $repo --public --description 'Aura Home Android launcher updates' --add-readme
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create the update repository.' }
    } elseif (($repoInfo | ConvertFrom-Json).visibility -ne 'PUBLIC') { throw 'The repository is private. This publisher will not change its visibility.' }
    $tag = 'build-' + [int]$metadata.versionCode
    & $ghPath release view $tag --repo $repo 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'This version already exists. Build a higher versionCode before publishing another update.' }
    $notes = Join-Path $root 'release-notes.md'
    & $ghPath release create $tag $apk (Join-Path $root 'release.json') --repo $repo --draft --title ('Aura Home ' + $metadata.versionName) --notes-file $notes
    if ($LASTEXITCODE -ne 0) { throw 'Upload failed. The version was not published.' }
    $assetText = & $ghPath release view $tag --repo $repo --json assets
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the uploaded draft.' }
    $assets = ($assetText | ConvertFrom-Json).assets
    $uploaded = @($assets | Where-Object { $_.name -eq 'Aura-Home.apk' })
    if ($uploaded.Count -ne 1 -or $uploaded[0].size -ne (Get-Item -LiteralPath $apk).Length) { throw 'Uploaded APK size did not match. The release remains a draft.' }
    & $ghPath release edit $tag --repo $repo --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Publishing failed. The release remains a draft.' }
    Write-Host ('Published: https://github.com/' + $repo + '/releases/tag/' + $tag)
    Write-Host 'Phones with Aura 1.3+ will find it during their next background check. Installation is manual.'
} catch {
    Write-Host ('ERROR: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
