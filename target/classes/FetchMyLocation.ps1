Add-Type -AssemblyName System.Device;
        $watcher = New-Object System.Device.Location.GeoCoordinateWatcher;
        $watcher.Start();
        while (($watcher.Status -ne 'Ready') -and ($watcher.Permission -ne 'Denied')) { Start-Sleep -Milliseconds 100 };
        if ($watcher.Permission -eq 'Denied') { Write-Output 'DENIED' }
        else { $loc = $watcher.Position.Location;
        Write-Output ('LOCATION:{0},{1}' -f $loc.Latitude, $loc.Longitude) };