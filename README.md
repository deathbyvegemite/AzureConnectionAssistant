# Azure Connection Assistant
This project contains both a PowerShell Module and a Library for assisting with connecting to and validating a connection to an Azure RM Subscription.

To use the module, copy the AzureConnectionAssistant folder from within the Module folder of the project to the following directory - '%Windir%\System32\WindowsPowerShell\v1.0\Modules'
<br />**or**<br />
Install from the PSGallery from an internet connected machine by typing Install-Module -Name AzureConnectionAssistant from an active PowerShell session.

To use the library, save the AzureConnectionHelperLibrary.ps1 file to a local scripting directory and dot source the file into your session.

The AzureRM PowerShell modules are a prerequisite for the connection to Azure.

PLEASE NOTE: As of v0.9, the Library version of this project is not being updated.

To raise issues or make feature requests - https://github.com/deathbyvegemite/AzureConnectionAssistant/issues

Created by: Scott Thomas - scott@deathbyvegemite.com
Copyright (c) 2017. All rights reserved.	

THIS CODE IS MADE AVAILABLE AS IS, WITHOUT WARRANTY OF ANY KIND. THE ENTIRE RISK
OF THE USE OR THE RESULTS FROM THE USE OF THIS CODE REMAINS WITH THE USER.


### DESCRIPTION
The following functions allows for the saving and retrieving of encrypted credentials from the HKCU context of the logged in user and for connecting or validating a connection to an Azure RM Subscription.

	Test-Session:		Initiates a check to see if you are have a current Azure Login session, if it finds you don't, it prompts you to select credentials saved in the Registry.

	New-AzureRMLogin:	Used by Test-Session to connect to Azure using credentials saved in the Registry of the user.

	Get-SavedCreds:		Retrieves a credential that is stored in the registry.

	Show-SavedCreds:	Displays credentials that are stored in the registry.

	New-SavedCreds:		Used to add a new credential set to the registry.

	Update-SavedCreds:		Used to update an exisiting stored credentual.

	Remove-SavedCreds:	Used to remove an exisiting stored credential.


### EXAMPLES
#### Test-Session
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
	
#### Get-SavedCreds
	PS C:\> $creds = Get-SavedCreds MyAzureCreds
	PS C:\> $creds
	UserName                                Password
	--------                                --------
	scott@examplenotreal.com System.Security.SecureString

	PS C:\>
	
#### Show-SavedCreds
	PS C:\> Show-SavedCreds -ShowPasswords

	Name			UserName					Password
	----			--------					--------
	MyAzureCreds	scott@examplenotreal.com	P@s$W0rd!

	PS C:\>
	
#### New-SavedCreds
	PS C:\> $creds = Get-Credential scott@examplenotreal.com
	PS C:\> New-SavedCreds -CredName MyAzureCreds -Creds $creds
	True

	PS C:\>

#### Update-SavedCreds
	PS C:\> $creds = Get-Credential scott@examplenotreal.com
	PS C:\> Update-SavedCreds -CredName MyAzureCreds -Creds $creds
	True

	PS C:\>

#### Remove-SavedCreds
	PS C:\> Remove-SavedCreds -CredName MyAzureCreds
	
	Confirm
	Are you sure you want to perform this action?
	Performing the operation "Remove-SavedCreds" on target "Are you sure?".
	[Y] Yes  [A] Yes to All  [N] No  [L] No to All  [S] Suspend  [?] Help (default is "Y"): Y
	MyAzureCreds has been removed
	True

	PS C:\>