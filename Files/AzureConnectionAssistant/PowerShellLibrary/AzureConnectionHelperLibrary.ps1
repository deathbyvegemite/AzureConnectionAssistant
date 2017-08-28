<#
.SYNOPSIS
Library to assist with connecting to and validating a connection to an Azure RM Subscription.
v0.9

.DESCRIPTION
The following functions allows for the saving and retrieving of encrypted credentials from the HKCU context of the logged in user.

Test-Session:		Initiates a check to see if you are have a current Azure Login session, if it finds you don't, it prompts you to select credentials saved in the Registry.

New-AzureRMLogin:	Used by Test-Session to connect to Azure using credentials saved in the Registry of the user.

Get-SavedCreds:		Retrieves a credential that is stored in the registry.

Show-SavedCreds:	Displays credentials that are stored in the registry.

New-SavedCreds:		Used to add a new credential set to the registry.

Set-SavedCreds:		Used to update an exisiting stored credentual.

This library is designed to be dot sourced and utilized by other scripts or directly from a PowerShell command console.

Functions:
 Test-Session

 New-AzureRMLogin [-ConnectWithDefault] [-WhatIf] [-Confirm]  [<CommonParameters>]

 Get-SavedCreds [-CredName] <string> [-WhatIf] [-Confirm]  [<CommonParameters>]
 
 Show-SavedCreds [-ShowPasswords] [-WhatIf] [-Confirm]  [<CommonParameters>]
 
 New-SavedCreds [-CredName] <string> [-Creds] <pscredential> [-WhatIf] [-Confirm]  [<CommonParameters>]
 
 Set-SavedCreds [-CredName] <string> [-Creds] <pscredential> [-WhatIf] [-Confirm]  [<CommonParameters>]

.EXAMPLE
PS C:\>. .\Scripts\AzureConnectionHelperLibrary.ps1 -Silent
Assuming the library is stored in a directory called "Scripts" that is in the root of the C drive.

.PARAMETER Silence
Dot Sourcing this file with the [-Silent] switch will hide the file Definition information.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
[CmdletBinding()]
param
(
	[switch]$Silent
)

#Start of Code

#Establishes and enforces coding rules in expressions, scripts, and script blocks
Set-StrictMode -Version Latest

function Test-Session ()
{
<#
.SYNOPSIS
Function to validate a connection to an Azure RM Subscription.

.DESCRIPTION
This function initiates a check to see if you are have a current Azure Login session, if it
finds you don't, it prompts you to select credentials saved in the Registry.

.EXAMPLE
	PS C:\> Test-Session
	No session found or No local credentials stored.
	Please select from the following
	1: MyAzureCreds
	2: To enter credentials manually (Needed for any Federated credentials)
	Select: : 1

	Environment           : AzureCloud
	Account               : scott@examplenotreal.com
	TenantId              : 123e7e65-2654-43c1-b123-caf99f844a69
	SubscriptionId        : 5095e43d-2fee-4c98-bd73-b7c5c7e01012
	SubscriptionName      : Pay-As-You-Go
	CurrentStorageAccount :

	PS C:\>

This example demonstrates running the function in a fresh PowerShell session, with no current
connection. The Function lists the saved credentials that are stored locally and prompts for a
selection as to which credential to use. Then proceeds to make a connection to AzureRM with
the selected credentials.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	trap{Write-Host -f Red "$($_.Exception.Message)"; return $false}
	$sesh = Get-AzureRmContext -ErrorAction SilentlyContinue
	if ($Sesh.Environment -like $null)
	{
		Write-Host -f Yellow "No session found or No local credentials stored."
		New-AzureRMLogin
	}
}


function New-AzureRMLogin
{
<#
.SYNOPSIS
Function to connect to an AzureRM Subscription.

.DESCRIPTION
This function is used by Test-Session to connect to Azure using credentials saved in the Registry of the current user.

.PARAMETER ConnectWithDefault
This switch will force the use of the first saved credential.

.EXAMPLE
PS C:\> New-AzureRMLogin
Please select from the following
1: MyAzureCreds
2: To enter credentials manually (Needed for any Federated credentials)
Select: : 1

Environment           : AzureCloud
Account               : scott@examplenotreal.com
TenantId              : 123e7e65-2654-43c1-b123-caf99f844a69
SubscriptionId        : 5095e43d-2fee-4c98-bd73-b7c5c7e01012
SubscriptionName      : Pay-As-You-Go
CurrentStorageAccount :
	
PS C:\>

This example demonstrates connecting to an Azure RM Subscription, after being prompted and selecting the first saved credentials.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	[CmdletBinding(SupportsShouldProcess = $true)]
	Param
	(
		[Parameter(Mandatory = $false, ValueFromPipeline = $false)][Switch]$ConnectWithDefault
	)
	trap { Write-Host -f Red "$($_.Exception.Message)"; return $false }
	if ((Test-Path -Path HKCU:\System\CurrentControlSet\SecCreds) -eq $false){return $false}
	if ($ConnectWithDefault)
	{
		$Default = (Show-SavedCreds)[0].Name
		$creds = Get-SavedCreds $Default
	}
	else
	{
		Write-Host "Please select from the following"
		$i = 1; Foreach ($Name in (Show-SavedCreds | select Name)) { Write-Host "$i`: $($name.name)"; $i++ }
		Write-Host "$i`: To enter credentials manually (Needed for any Federated credentials)"
		$promptvalue = Read-Host -Prompt "Select: "
		if ($promptvalue -eq $i)
		{
			$return = Login-AzureRmAccount
			return $return
		}
		else
		{ 
			$CredToConnectTo = (List-SavedCreds)[($promptvalue - 1)]
			$creds = Get-SavedCreds $($CredToConnectTo.name)
		}
	}	
	$return = Login-AzureRmAccount -Credential $creds
	return $return
}


function New-SavedCreds
{
<#
.SYNOPSIS
Function to save credentials to the HKCU.

.DESCRIPTION
This function will save an encrypted credential to the HKCU hive of the current users' context.

.PARAMETER CredName
This is the name used to save the credentials under in the registry.

.PARAMETER Creds
This is an object containing a PSCredential.

.EXAMPLE
PS C:\> $creds = Get-Credential scott@examplenotreal.com
PS C:\> New-SavedCreds -CredName MyAzureCreds -Creds $creds
	Hive: HKEY_CURRENT_USER\System\CurrentControlSet\SecCreds
Name                           Property
----                           --------
MyAzureCreds

UserName     : scott@examplenotreal.com
PSPath       : Microsoft.PowerShell.Core\Registry::HKEY_CURRENT_USER\System\CurrentControlSet\SecCreds\MyAzureCreds
PSParentPath : Microsoft.PowerShell.Core\Registry::HKEY_CURRENT_USER\System\CurrentControlSet\SecCreds
PSChildName  : MyAzureCreds
PSDrive      : HKCU
PSProvider   : Microsoft.PowerShell.Core\Registry

Password     : cAOSp3ihzlAtXC8vSzI9TYBHHpGGIV3SnbCQMJJQMZ7AjPDkcIXL0UpKqZ1tw1TLstQtIsUhGhHsfntYYnz1eKEMAh1vuR5vy9oPRkgNA3LibSINV2Ku4AYIKwwSW5sAefEYaxrxAPOsY2OOgX1B0w6KHUShEpy9U2HQxiOSEk
			   tDR12J9Ir1q4NCALIvpnfB6iEMFYJfY80bqvyTjmcpTlBpVNbja2rGHeXGj5yzWOeuluSqH6MX9IT963Ruoy1QPYIJSiWN8KIEDvbLs8vciGaU4v3o2G1gajl0KY5iuQ32p8sbwiIU8RzjfPg9Hmi5f3mt
PSPath       : Microsoft.PowerShell.Core\Registry::HKEY_CURRENT_USER\System\CurrentControlSet\SecCreds\MyAzureCreds
PSParentPath : Microsoft.PowerShell.Core\Registry::HKEY_CURRENT_USER\System\CurrentControlSet\SecCreds
PSChildName  : MyAzureCreds
PSDrive      : HKCU
PSProvider   : Microsoft.PowerShell.Core\Registry

PS C:\>

This example demonstrates saving a set of credentuals to a variable, then adding that PSCredential to the registry.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	[CmdletBinding(SupportsShouldProcess = $true)]
	Param
	(
		[Parameter(Mandatory = $true, ValueFromPipeline = $false)][String]$CredName,
		[Parameter(Mandatory = $true, ValueFromPipeline = $false)][System.Management.Automation.PSCredential]$Creds
	)
	trap { Write-Host -f Red "$($_.Exception.Message)"; return $false }
	if ((Test-Path -Path HKCU:\System\CurrentControlSet\SecCreds) -eq $false)
	{
		New-Item -Path HKCU:\System\CurrentControlSet\SecCreds
	}
	New-Item -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName
	New-ItemProperty -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName -Name UserName -Value $creds.UserName
	$password = $creds.Password | ConvertFrom-SecureString
	New-ItemProperty -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName -Name Password -Value $password
	return $true
}


function Get-SavedCreds
{
<#
.SYNOPSIS
Function to save retrieve saved credentials.

.DESCRIPTION
This function will retrieve a set of credentials that are stored in the local users registry.

.PARAMETER CredName
This is the name of the credential to retrieve from the registry.

.EXAMPLE
PS C:\> $creds = Get-SavedCreds MyAzureCreds
PS C:\> $creds
UserName                                Password
--------                                --------
scott@examplenotreal.com System.Security.SecureString

PS C:\>

This example demonstrates getting a credential from the registry and saving it to a variable.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	[CmdletBinding(SupportsShouldProcess = $true)]
	Param
	(
		[Parameter(Mandatory = $true, ValueFromPipeline = $false)][String]$CredName
	)
	trap { Write-Host -f Red "$($_.Exception.Message)"; return $false }
	$test = Test-Path -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName
	if ($test)
	{
		$userName = (Get-ItemProperty -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName -Name UserName).UserName
		$password = (Get-ItemProperty -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName -Name Password).password | ConvertTo-SecureString
		$creds = New-Object System.Management.Automation.PSCredential $userName, $password
		return $creds
	}
	else
	{
		Write-Host -f Red "Credential $($CredName) not found on machine."
		return $false
	}
	return $true
}


function Set-SavedCreds
{
<#
.SYNOPSIS
Function to update a saved credentials.

.DESCRIPTION
This function allows for the updating of an exisitng saved credential.

.PARAMETER CredName
This is the name of the credential to be updated.

.PARAMETER Creds
This is an object containing a new PSCredential.

.EXAMPLE
PS C:\> $creds = Get-Credential scott@examplenotreal.com
PS C:\> Set-SavedCreds -CredName MyAzureCreds -Creds $creds
True
PS C:\>

This example demonstrates updadting a saved credential to new values.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	[CmdletBinding(SupportsShouldProcess = $true)]
	Param
	(
		[Parameter(Mandatory = $true, ValueFromPipeline = $false)][String]$CredName,
		[Parameter(Mandatory = $true, ValueFromPipeline = $false)][System.Management.Automation.PSCredential]$Creds
	)
	trap { Write-Host -f Red "$($_.Exception.Message)"; return $false }
	$test = Test-Path -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName
	if ($test)
	{
		if ($creds)
		{
			$password = $creds.Password | ConvertFrom-SecureString
			Set-ItemProperty -Path HKCU:\System\CurrentControlSet\SecCreds\$CredName -Name Password -Value $password
		}
	}
	else
	{
		Write-Host -f Red "Credential $($CredName) not found on machine."
		return $false
	}
	return $true
}


function Show-SavedCreds
{
<#
.SYNOPSIS
Function to show all saved credentials.

.DESCRIPTION
This function will retrieve allcredentials that are stored in the local users registry and display them on screen.

.PARAMETER ShowPasswords
This switch will allow for the credentials to be decrypted and displayed on screen.

.EXAMPLE
PS C:\> Show-SavedCreds -ShowPasswords

Name			UserName					Password
----			--------					--------
MyAzureCreds	scott@examplenotreal.com	P@s$W0rd!

PS C:\>

This example demonstrates listing all credential from the registry and displaying passwords.

.NOTES
Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.
#>
	[CmdletBinding(SupportsShouldProcess = $true)]
	Param
	(
		[Parameter(Mandatory = $false, ValueFromPipeline = $false)][Switch]$ShowPasswords = $false
	)
	trap { Write-Host -f Red "$($_.Exception.Message)"; return $false }
	$tmpContent = @()
	$objReturn = @()
	$tmpContent = Get-ChildItem HKCU:\System\CurrentControlSet\SecCreds\
	foreach ($C in $tmpContent)
	{
		$tmp = $C.name.split("\")[-1]
		If ($ShowPasswords)
		{
			$password = $C.GetValue("Password") | ConvertTo-SecureString
			[String]$stringValue = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($password))
		}
		else
		{
			$stringValue = "**********"
		}
		$objTemp = New-Object -TypeName PSobject
		$objTemp | Add-Member -MemberType NoteProperty -Name Name $tmp
		$objTemp | Add-Member -MemberType NoteProperty -Name UserName $C.GetValue("UserName")
		$objTemp | Add-Member -MemberType NoteProperty -Name Password $stringValue
		
		$objReturn += $objTemp
	}
	return $objReturn
}

#Hides the libraries definition output on load
if (-not $Silent)
{
	help $MyInvocation.mycommand.Definition
}
##End Of Code