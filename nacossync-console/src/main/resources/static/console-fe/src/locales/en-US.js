const I18N_CONF = {
  Header: {
    home: 'HOME',
    docs: 'DOCS',
    blog: 'BLOG',
    community: 'COMMUNITY',
    languageSwitchButton: '中',
  },
  Menu: {
    serviceSync: 'Service Synchronization',
    clusterConfig: 'Cluster Configuration',
    systemConfig: 'System configuration',
  },
  ClusterConfig: {
    title: 'Cluster Configuration',
    addCluster: 'New Cluster',
    search: 'Search',
    clusterName: 'Cluster Name',
    clusterNamePlaceholder: 'Please enter the cluster name',
    clusterType: 'Cluster Type',
    connectKeyList: 'Connect Key List',
    namespace: 'Namespace',
    operation: 'Operation',
    deleteBtn: 'Delete',
    confirm: 'Prompt',
    confirmMsg: 'Are you sure you want to delete this row？',
    successMsg: 'Delete the success!',
  },
  AddConfigDialog: {
    title: 'New Cluster',
    clusterName: 'Cluster Name',
    clusterNamePlaceholder: 'Please enter the cluster name',
    namespace: 'Namespace',
    namespacePlaceholder: 'Please enter the namespace',
    clusterType: 'Cluster Type',
    connectKeyList: 'Connect IP',
    connectKeyListPlaceholder: 'Please enter the ip',
  },
  ServiceSync: {
    title: 'Service Synchronization',
    serviceNamePlaceholder: 'Please enter service name',
    search: 'Search',
    addSync: 'New Sync',
    serviceName: 'Service Name',
    groupName: 'Group',
    sourceCluster: 'Source Cluster',
    destCluster: 'Dest Cluster',
    instancesCount: 'Instances Count',
    operation: 'Operation',
    deleteBtn: 'Delete',
    suspendedBtn: 'Suspend',
    resynchronizeBtn: 'Resynchronize',
    confirm: 'Prompt',
    confirmMsg: 'Are you sure you want to delete this row？',
    suspendedMsg: 'Are you sure you want to pause this row？',
    successMsg: 'Suspension of success!',
    deleteSuccessMsg: 'Delete the success!',
    syncSuccessMsg: 'Resync succeeded!',
  },
  AddSyncDialog: {
    title: 'New Sync',
    serviceName: 'Service Name',
    serviceNamePlaceholder: 'Please enter service name',
    groupName: 'Group Name',
    groupNamePlaceholder: 'Please enter group name',
    sourceCluster: 'Source Cluster',
    destCluster: 'Dest Cluster',
    version: 'Version',
    versionPlaceholder: 'Please enter version',
  },
  SystemConfig: {
    title: 'System configuration',
    configName: 'Config Name',
    configNamePlaceholder: 'Please enter config name',
    search: 'Search',
    addConfig: 'New SysConfig',
    value: 'Value',
    operation: 'Operation',
    deleteBtn: 'Delete',
    confirm: 'Prompt',
    confirmMsg: 'Are you sure you want to delete this row？',
    successMsg: 'Delete the success!',
  },
  AddSysConfigDialog: {
    title: 'New System configuration',
    configName: 'Config Name',
    configNamePlaceholder: 'Please enter config name',
    configValue: 'Config Value',
    configValuePlaceholder: 'Please enter config value',
  },
};
export default I18N_CONF;
