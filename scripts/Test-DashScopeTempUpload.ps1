[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $FilePath,

    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $Model = 'qwen-audio-3.1-asr-flash-filetrans',

    [Parameter()]
    [switch] $Upload
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$apiKey = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY')
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw '未设置 DASHSCOPE_API_KEY 环境变量。请先在当前 PowerShell 会话中设置它。'
}

$resolvedPath = (Resolve-Path -LiteralPath $FilePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    throw "文件不存在或不是普通文件：$FilePath"
}

$fileInfo = Get-Item -LiteralPath $resolvedPath
if ($fileInfo.Length -gt 1GB) {
    throw "文件大小为 $($fileInfo.Length) bytes，超过百炼临时文件上传接口的 1GB 限制。"
}
if ($fileInfo.Length -eq 0) {
    throw '文件为空，拒绝请求。'
}

Add-Type -AssemblyName System.Net.Http

function Get-SafeBody {
    param([string] $Body, [string] $Secret)

    if ([string]::IsNullOrWhiteSpace($Body)) { return '<empty response body>' }
    $safe = $Body.Replace($Secret, '[REDACTED]')
    $safe = [regex]::Replace($safe, '(?i)("?(?:signature|policy|oss_access_key_id|OSSAccessKeyId|authorization|access_key|secret)"?\s*[:=]\s*")[^"]*(")', '$1[REDACTED]$2')
    $safe = [regex]::Replace($safe, '(?i)(Bearer\s+)[A-Za-z0-9._-]+', '$1[REDACTED]')
    if ($safe.Length -gt 4000) { $safe = $safe.Substring(0, 4000) + ' ...[truncated]' }
    return $safe
}

function Invoke-HttpAndReadBody {
    param(
        [System.Net.Http.HttpClient] $Client,
        [System.Net.Http.HttpRequestMessage] $Request,
        [string] $Stage,
        [string] $Secret
    )

    $response = $null
    try {
        $response = $Client.SendAsync($Request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $status = [int]$response.StatusCode
            $safeBody = Get-SafeBody -Body $body -Secret $Secret
            throw ("$Stage 失败：HTTP $status" + [Environment]::NewLine + "响应正文：$safeBody")
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $body
        }
    }
    catch {
        if ($_.Exception.Message -match 'HTTP \d{3}') { throw }
        throw "$Stage 请求异常：$($_.Exception.Message)"
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
    }
}

$baseUri = 'https://dashscope.aliyuncs.com/api/v1/uploads'
$queryModel = [uri]::EscapeDataString($Model)
$policyUri = "$baseUri?action=getPolicy&model=$queryModel"

$handler = [System.Net.Http.HttpClientHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(5)

try {
    $policyRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $policyUri)
    $policyRequest.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
    $policyRequest.Headers.Accept.ParseAdd('application/json')

    Write-Host "阶段 1/2：获取上传凭证（模型：$Model）..."
    $policyResult = Invoke-HttpAndReadBody -Client $client -Request $policyRequest -Stage '获取上传凭证' -Secret $apiKey
    $policyRequest.Dispose()

    try {
        $policyEnvelope = $policyResult.Body | ConvertFrom-Json
        $policy = $policyEnvelope.data
    }
    catch {
        throw "获取上传凭证成功（HTTP $($policyResult.StatusCode)），但响应不是有效 JSON。响应正文：$(Get-SafeBody -Body $policyResult.Body -Secret $apiKey)"
    }

    $required = @('upload_host', 'upload_dir', 'oss_access_key_id', 'signature', 'policy', 'x_oss_object_acl', 'x_oss_forbid_overwrite')
    $missing = @($required | Where-Object {
        $null -eq $policy -or [string]::IsNullOrWhiteSpace([string]$policy.$_)
    })
    if ($missing.Count -gt 0) {
        $available = if ($null -eq $policy) { '<data missing>' } else { ($policy.PSObject.Properties.Name -join ', ') }
        throw "上传凭证响应缺少必填字段：$($missing -join ', ')。响应 data 实际字段：$available。为避免误传，不执行上传。"
    }

    $uploadUri = [uri]$policy.upload_host
    if ($uploadUri.Scheme -ne 'https') {
        throw "上传地址不是 HTTPS，拒绝继续：$($uploadUri.Scheme)://$($uploadUri.Host)"
    }

    Write-Host '获取上传凭证成功。'
    Write-Host "上传主机：$($uploadUri.Scheme)://$($uploadUri.Host)"
    Write-Host "凭证字段检查：$($required.Count)/$($required.Count) 项齐全（具体凭证值已隐藏）。"
    Write-Host "本地文件：$($fileInfo.Name)"
    Write-Host ('文件大小：{0:N0} bytes' -f $fileInfo.Length)
    Write-Host "模型：$Model"

    if (-not $Upload) {
        Write-Host ''
        Write-Host '当前为诊断模式：未上传文件，也未提交转写任务。'
        Write-Host '确认要实际上传时，请重新运行并添加 -Upload；PowerShell 会在发送文件前请求确认。'
        return
    }

    $extension = [System.IO.Path]::GetExtension($fileInfo.Name)
    if ($extension -notmatch '^\.[A-Za-z0-9]{1,10}$') { $extension = '.bin' }
    $safeFileName = 'asr-' + [guid]::NewGuid().ToString('N') + $extension.ToLowerInvariant()
    $ossKey = ([string]$policy.upload_dir).TrimEnd('/') + '/' + $safeFileName

    if (-not $PSCmdlet.ShouldProcess("$($uploadUri.Host)/$ossKey", "上传 $($fileInfo.Name)（$($fileInfo.Length) bytes）")) {
        Write-Host '已跳过文件上传；没有提交转写任务。'
        return
    }

    # MultipartFormDataContent 自动生成 boundary；不得手工覆盖 Content-Type。
    # 严格按官网示例添加字段，并把 file 作为最后一个 part。
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $fileStream = $null
    $fileContent = $null
    $uploadRequest = $null

    try {
        $textFields = @(
            @{ Name = 'OSSAccessKeyId'; Value = [string]$policy.oss_access_key_id },
            @{ Name = 'Signature'; Value = [string]$policy.signature },
            @{ Name = 'policy'; Value = [string]$policy.policy },
            @{ Name = 'x-oss-object-acl'; Value = [string]$policy.x_oss_object_acl },
            @{ Name = 'x-oss-forbid-overwrite'; Value = [string]$policy.x_oss_forbid_overwrite },
            @{ Name = 'key'; Value = $ossKey },
            @{ Name = 'success_action_status'; Value = '200' }
        )

        foreach ($field in $textFields) {
            $partBytes = [System.Text.Encoding]::UTF8.GetBytes($field.Value)
            $part = [System.Net.Http.ByteArrayContent]::new($partBytes)
            $multipart.Add($part, $field.Name)
        }

        $mimeType = switch ($extension.ToLowerInvariant()) {
            '.m4a' { 'audio/mp4' }
            '.mp4' { 'audio/mp4' }
            '.mp3' { 'audio/mpeg' }
            '.wav' { 'audio/wav' }
            '.aac' { 'audio/aac' }
            '.amr' { 'audio/amr' }
            '.ogg' { 'audio/ogg' }
            '.flac' { 'audio/flac' }
            default { 'application/octet-stream' }
        }

        $fileStream = [System.IO.File]::OpenRead($resolvedPath)
        $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($mimeType)
        $fileContent.Headers.ContentDisposition = [System.Net.Http.Headers.ContentDispositionHeaderValue]::new('form-data')
        $fileContent.Headers.ContentDisposition.Name = '"file"'
        $fileContent.Headers.ContentDisposition.FileName = '"' + $safeFileName + '"'
        $multipart.Add($fileContent)

        $uploadRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uploadUri)
        $uploadRequest.Content = $multipart

        Write-Host '阶段 2/2：向 OSS 上传文件（不会自动提交 ASR 转写任务）...'
        $uploadResult = Invoke-HttpAndReadBody -Client $client -Request $uploadRequest -Stage 'OSS 表单上传' -Secret $apiKey
        Write-Host "OSS 上传成功：HTTP $($uploadResult.StatusCode)"
        Write-Host "临时文件 URL：oss://$ossKey"
        Write-Host '注意：该临时 URL 有效期 48 小时，后续调用必须带 X-DashScope-OssResourceResolve: enable，并使用相同模型及同一主账号的 API Key。'
        Write-Host '此脚本到此结束：未提交转写任务。'
    }
    finally {
        if ($null -ne $uploadRequest) { $uploadRequest.Dispose() }
        if ($null -ne $fileContent) { $fileContent.Dispose() }
        elseif ($null -ne $fileStream) { $fileStream.Dispose() }
        $multipart.Dispose()
    }
}
finally {
    if ($null -ne $client) { $client.Dispose() }
    if ($null -ne $handler) { $handler.Dispose() }
}
