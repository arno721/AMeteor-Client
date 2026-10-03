# Meteor Client music bridge.
# Reads what is playing from the Windows media controls (the same info as the volume flyout) and prints one JSON object
# per line. Reads commands from stdin, one per line: play, pause, toggle, next, previous, seek <seconds>, select <app>.
# Started and stopped by the Music Player module, it exits on its own when the game closes its stdin.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskOperation = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($operation, [Type]$type) {
    $task = $asTaskOperation.MakeGenericMethod($type).Invoke($null, @($operation))
    if (-not $task.Wait(3000)) { throw 'timeout' }
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]

$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]

$manager = Await ($managerType::RequestAsync()) $managerType

$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
$readTask = $stdin.ReadLineAsync()

$preferred = ''
$lastKey = $null
$lastLine = ''
$lastSent = [DateTime]::MinValue

function Pick-Session {
    $sessions = @($manager.GetSessions())
    if ($sessions.Count -eq 0) { return $null }

    if ($preferred -ne '') {
        foreach ($s in $sessions) {
            if ($s.SourceAppUserModelId -like "*$preferred*") { return $s }
        }
    }

    $current = $manager.GetCurrentSession()
    if ($current -ne $null) { return $current }
    return $sessions[0]
}

function Read-Thumbnail($properties) {
    if ($properties.Thumbnail -eq $null) { return '' }

    try {
        $stream = Await ($properties.Thumbnail.OpenReadAsync()) $streamType
        $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $memory = New-Object System.IO.MemoryStream
        $net.CopyTo($memory)
        $net.Dispose()
        if ($memory.Length -gt 4MB) { return '' }
        return [Convert]::ToBase64String($memory.ToArray())
    } catch {
        return ''
    }
}

while ($true) {
    # Commands from the game
    while ($readTask.IsCompleted) {
        $command = $readTask.Result
        if ($command -eq $null) { exit 0 }
        $readTask = $stdin.ReadLineAsync()

        try {
            $session = Pick-Session
            $parts = $command.Trim().Split(' ', 2)

            switch ($parts[0]) {
                'select' { $preferred = if ($parts.Count -gt 1) { $parts[1] } else { '' }; $lastKey = $null }
                'play' { if ($session) { $null = Await ($session.TryPlayAsync()) ([bool]) } }
                'pause' { if ($session) { $null = Await ($session.TryPauseAsync()) ([bool]) } }
                'toggle' { if ($session) { $null = Await ($session.TryTogglePlayPauseAsync()) ([bool]) } }
                'next' { if ($session) { $null = Await ($session.TrySkipNextAsync()) ([bool]) } }
                'previous' { if ($session) { $null = Await ($session.TrySkipPreviousAsync()) ([bool]) } }
                'seek' { if ($session -and $parts.Count -gt 1) { $null = Await ($session.TryChangePlaybackPositionAsync([long]([double]$parts[1] * 10000000))) ([bool]) } }
            }
        } catch { }
    }

    $out = [ordered]@{}

    try {
        $session = Pick-Session
        $apps = @($manager.GetSessions() | ForEach-Object { $_.SourceAppUserModelId })
        $out.apps = $apps

        if ($session -eq $null) {
            $out.active = $false
        } else {
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propertiesType
            $timeline = $session.GetTimelineProperties()
            $playback = $session.GetPlaybackInfo()

            $out.active = $true
            $out.app = $session.SourceAppUserModelId
            $out.title = [string]$properties.Title
            $out.artist = [string]$properties.Artist
            $out.album = [string]$properties.AlbumTitle
            $out.albumArtist = [string]$properties.AlbumArtist
            $out.track = [int]$properties.TrackNumber
            $out.tracks = [int]$properties.AlbumTrackCount
            $out.status = [string]$playback.PlaybackStatus
            $out.shuffle = if ($playback.IsShuffleActive -ne $null) { [bool]$playback.IsShuffleActive } else { $false }
            $out.repeat = if ($playback.AutoRepeatMode -ne $null) { [string]$playback.AutoRepeatMode } else { 'None' }
            $out.rate = if ($playback.PlaybackRate -ne $null) { [double]$playback.PlaybackRate } else { 1.0 }
            $out.position = $timeline.Position.TotalSeconds
            $out.start = $timeline.StartTime.TotalSeconds
            $out.end = $timeline.EndTime.TotalSeconds
            # How long ago the position was measured, the game moves it forward from there
            $out.age = ([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalSeconds
            $out.canNext = $playback.Controls.IsNextEnabled
            $out.canPrevious = $playback.Controls.IsPreviousEnabled

            $key = "$($out.app)|$($out.title)|$($out.artist)|$($out.album)"
            if ($key -ne $lastKey) {
                $out.thumbnail = Read-Thumbnail $properties
                # Some players set the cover a moment after the title
                if ($out.thumbnail -ne '' -or $lastKey -eq $null -or $key.Split('|')[1] -eq '') { $lastKey = $key }
            }
        }
    } catch {
        $out = [ordered]@{ active = $false; error = $_.Exception.Message }
    }

    $line = $out | ConvertTo-Json -Compress -Depth 3
    $compare = $line -replace '"age":[^,}]*', '' -replace '"position":[^,}]*', ''
    $now = [DateTime]::Now

    # Only what changed, plus a heartbeat so the position stays in sync
    if ($compare -ne $lastLine -or ($now - $lastSent).TotalSeconds -ge 1.0 -or $out.Contains('thumbnail')) {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
        $lastLine = $compare
        $lastSent = $now
    }

    Start-Sleep -Milliseconds 250
}
