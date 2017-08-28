<#
	.Synopsis
	Checks for a valid connection to an Azure RM Subscription.
	
	.Description
	This function runs a simple test to validate if a connection to an Azure RM Subscription exists,
	if it doesn't, it attempts to start one

	.Example

#>
function Test-Session ()
{
	trap{Write-Host -f Red "$($_.Exception.Message)"; return $false}
	$sesh = Get-AzureRmContext -ErrorAction SilentlyContinue
	if ($Sesh.Environment -like $null)
	{
		Write-Host -f Yellow "No session found or No local credentials stored."
		New-AzureRMLogin
	}
}