param(
    [Parameter()]
    [ValidatePattern('^[a-z0-9]{20}$')]
    [string]$ProjectRef = 'vhppdgbmmtfrbzocitob'
)

$smtpHost = Read-Host 'Servidor SMTP [Enter = smtp.sendgrid.net]'
if ([string]::IsNullOrWhiteSpace($smtpHost)) { $smtpHost = 'smtp.sendgrid.net' }
$smtpPortText = Read-Host 'Puerto SMTP [Enter = 587]'
if ([string]::IsNullOrWhiteSpace($smtpPortText)) { $smtpPortText = '587' }
$smtpPort = 0
if (-not [int]::TryParse($smtpPortText, [ref]$smtpPort) -or $smtpPort -lt 1 -or $smtpPort -gt 65535) {
    throw 'Puerto SMTP inválido.'
}
$smtpUser = Read-Host 'Usuario SMTP [Enter = apikey]'
if ([string]::IsNullOrWhiteSpace($smtpUser)) { $smtpUser = 'apikey' }
$senderAddress = Read-Host 'Correo remitente verificado en tu proveedor'
$senderName = Read-Host 'Nombre remitente (Enter para TzindaApp)'
if ([string]::IsNullOrWhiteSpace($senderName)) { $senderName = 'TzindaApp' }

$patSecure = Read-Host 'Supabase Personal Access Token (no se mostrara)' -AsSecureString
$smtpPassSecure = Read-Host 'Twilio SendGrid API Key con Mail Send (no se mostrara)' -AsSecureString
$patPointer = [IntPtr]::Zero
$smtpPointer = [IntPtr]::Zero
$pat = $null
$smtpPass = $null

try {
    $patPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($patSecure)
    $smtpPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($smtpPassSecure)
    $pat = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($patPointer)
    $smtpPass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($smtpPointer)

    $uri = "https://api.supabase.com/v1/projects/$ProjectRef/config/auth"
    $headers = @{ Authorization = "Bearer $pat" }
    $payload = @{
        external_email_enabled = $true
        smtp_admin_email = $senderAddress
        smtp_host = $smtpHost
        smtp_port = $smtpPort
        smtp_user = $smtpUser
        smtp_pass = $smtpPass
        smtp_sender_name = $senderName
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
    if ($current.external_email_enabled -ne $true -or
        $template -notmatch '\{\{\s*\.Token\s*\}\}' -or
        $template -match '\.ConfirmationURL') {
        throw 'La verificación posterior no encontró SMTP externo activo y la plantilla OTP esperada.'
    }

    Write-Host "SMTP externo y plantilla OTP configurados y verificados en $ProjectRef."
    Write-Host 'La contraseña SMTP y el PAT no se guardaron en archivos ni se imprimieron.'
}
catch {
    $apiMessage = [string]$_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($apiMessage)) { $apiMessage = $_.Exception.Message }
    foreach ($secret in @($pat, $smtpPass)) {
        if (-not [string]::IsNullOrEmpty($secret)) { $apiMessage = $apiMessage.Replace($secret, '[DATO OCULTO]') }
    }
    Write-Error "No se pudo configurar SMTP y OTP. Respuesta: $apiMessage"
    exit 1
}
finally {
    if ($patPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($patPointer) }
    if ($smtpPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($smtpPointer) }
    $patSecure.Dispose()
    $smtpPassSecure.Dispose()
    $pat = $null
    $smtpPass = $null
}
