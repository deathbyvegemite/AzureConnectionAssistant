TOPIC

about_AzureConnectionAssistant

SHORT DESCRIPTION

A module to assist with connecting to and validating a connection to an Azure RM Subscription.

LONG DESCRIPTION

The following functions allows for the saving and retrieving of encrypted credentials from the HKLM context of the logged in user.

	Test-Session:		Initiates a check to see if you are have a current Azure Login session, if it finds you don't, it prompts you to select credentials saved in the Registry.

	New-AzureRMLogin:	Used by Test-Session to connect to Azure using credentials saved in the Registry of the user.

	Get-SavedCreds:		Retrieves a credential that is stored in the registry.

	Show-SavedCreds:	Displays credentials that are stored in the registry.

	New-SavedCreds:		Used to add a new credential set to the registry.

	Set-SavedCreds:		Used to update an exisiting stored credentual.

	Created by: Scott Thomas - scott@deathbyvegemite.com
	Copyright (c) 2017. All rights reserved.	

	THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
	OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.

EXAMPLES
# Test-Session
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


# Get-SavedCreds
	PS C:\> $creds = Get-SavedCreds MyAzureCreds
	PS C:\> $creds
	UserName                                Password
	--------                                --------
	scott@examplenotreal.com System.Security.SecureString

	PS C:\>


# Show-SavedCreds
	PS C:\> Show-SavedCreds -ShowPasswords

	Name			UserName					Password
	----			--------					--------
	MyAzureCreds	scott@examplenotreal.com	P@s$W0rd!

	PS C:\>


# New-SavedCreds
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


# Set-SavedCreds
	PS C:\> $creds = Get-Credential scott@examplenotreal.com
	PS C:\> Set-SavedCreds -CredName MyAzureCreds -Creds $creds
	True
	PS C:\>

KEYWORDS

Login-AzureRmAccount, AzureRM, SavedCreds.
