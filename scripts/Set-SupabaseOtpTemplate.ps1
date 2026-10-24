param(
    [Parameter()]
    [ValidatePattern('^[a-z0-9]{20}$')]
    [string]$ProjectRef = 'vhppdgbmmtfrbzocitob'
)

$secureToken = Read-Host 'Supabase Personal Access Token (no se mostrara)' -AsSecureString
$tokenPointer = [IntPtr]::Zero
$accessToken = $null

try {
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $accessToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)

    $uri = "https://api.supabase.com/v1/projects/$ProjectRef/config/auth"
    $headers = @{ Authorization = "Bearer $accessToken" }
    $payload = @{
        mailer_subjects_confirmation = 'Tu código para verificar TzindaApp'
        mailer_templates_confirmation_content = @'
<div style="font-family: Arial, sans-serif; color: #1c1c15; max-width: 520px; margin: 0 auto;">
  <h2>Verifica tu correo en TzindaApp</h2>
  <p>Escribe este código en la app para confirmar tu cuenta:</p>
  <p style="font-size: 32px; font-weight: 700; letter-spacing: 8px;">{{ .Token }}</p>
  <p>Si no solicitaste crear una cuenta, puedes ignorar este mensaje.</p>
</div>
'@
    } | ConvertTo-Json -Compress

    $null = Invoke-RestMethod -Method Patch -Uri $uri -Headers $headers -ContentType 'application/json' -Body $payload
    $current = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
    $template = [string]$current.mailer_templates_confirmation_content
    if ($template -notmatch '\{\{\s*\.Token\s*\}\}' -or $template -match '\.ConfirmationURL') {
        throw 'Supabase no devolvio la plantilla OTP esperada.'
    }

    Write-Host "Plantilla Confirm signup actualizada y verificada para el proyecto $ProjectRef."
    Write-Host 'La plantilla entrega {{ .Token }} y ya no usa ConfirmationURL.'
}
catch {
    $apiMessage = [string]$_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($apiMessage)) {
        $apiMessage = $_.Exception.Message
    }
    if (-not [string]::IsNullOrEmpty($accessToken)) {
        $apiMessage = $apiMessage.Replace($accessToken, '[TOKEN OCULTO]')
    }
    if ($apiMessage -match 'default email provider|Email template modification is not available') {
        $apiMessage += ' Este proyecto nuevo en Free requiere configurar SMTP externo o cambiar a un plan de pago antes de editar plantillas.'
    }
    Write-Error "No se pudo actualizar el template alojado. Respuesta de Supabase: $apiMessage"
    exit 1
}
finally {
    if ($tokenPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }
    $accessToken = $null
    $secureToken.Dispose()
}
