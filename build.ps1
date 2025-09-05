# Simple build script for Pstream MVP
# Download Gradle wrapper if missing
$wrapperJar = "gradle/wrapper/gradle-wrapper.jar"
$wrapperUrl = "https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar"

if (!(Test-Path $wrapperJar)) {
    Write-Host "Downloading Gradle wrapper..."
    try {
        Invoke-WebRequest -Uri $wrapperUrl -OutFile $wrapperJar
        Write-Host "Gradle wrapper downloaded successfully"
    } catch {
        Write-Host "Failed to download Gradle wrapper. Please download manually from:"
        Write-Host $wrapperUrl
        Write-Host "to $wrapperJar"
        exit 1
    }
}

# Try to build with gradlew.bat
if (Test-Path "gradlew.bat") {
    Write-Host "Building with Gradle wrapper..."
    & .\gradlew.bat assembleDebug
} else {
    Write-Host "gradlew.bat not found. Please run 'gradle assembleDebug' if Gradle is installed globally"
}
