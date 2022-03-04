
properties([
        parameters([
            booleanParam(
                        name: 'UPDATE_CCACHE',
                        defaultValue: true
            ),
            string(
                        defaultValue: 'ASan',
                        name: 'SANITIZER',
                        trim: true,
            ),
    ]),
    pipelineTriggers([
        parameterizedCron('''
            H 2 * * * % UPDATE_CCACHE=true SANITIZER=ASan
            H 2 * * * % UPDATE_CCACHE=true SANITIZER=TSan
        ''')
    ])
])

def checkout() {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    checkout(changelog: false, poll: false, scm: [
            $class                           : "GitSCM",
            branches                         : [
                    [name: "master"],
            ],
            userRemoteConfigs                : [
                    [
                            url          : "git@github.com:pingcap/tics.git",
                            refspec      : refspec,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions                       : [
                    [$class             : 'SubmoduleOption',
                     disableSubmodules  : false,
                     parentCredentials  : true,
                     recursiveSubmodules: true,
                     trackingSubmodules : false,
                     reference          : ''],
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
}

def runBuilderClosure(label, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 15, containers: [
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/docker:build-essential-java',
                    alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
            containerTemplate(name: 'builder', image: 'hub.pingcap.net/tiflash/tiflash-llvm-base:amd64',
                    alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                    resourceRequestCpu: '10000m', resourceRequestMemory: '32Gi',
                    resourceLimitCpu: '20000m', resourceLimitMemory: '64Gi'),
    ],
    volumes: [
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
    ]
    ) {
        node(label) {
            body()
        }
    }
}

def runCheckoutAndBuilderClosure(label, curws, Closure body) {
    runBuilderClosure(label) {
        dir("${curws}/tics") {
            stage("Checkout") {
                container("docker") {
                    def repoDailyCache = "/home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz"
                    if (fileExists(repoDailyCache)) {
                        println "get code from nfs to reduce clone time"
                        sh """
                        cp -R ${repoDailyCache}  ./
                        tar -xzf ${repoDailyCache} --strip-components=1
                        rm -f src-tics.tar.gz
                        """
                        sh "chown -R 1000:1000 ./"
                    } else {
                        sh "exit -1"
                    }
                }
                checkout()
            }
        }
        body()
    }
}

def prepareBuildCache(type, cwd) {
    def CI_PREPARE_SCRIPT = '''
#!/bin/bash
set -ueox pipefail


CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE:-Debug}
BUILD_BRANCH=${BUILD_BRANCH:-master}
UPDATE_CCACHE=${UPDATE_CCACHE:-false}
CCACHE_REMOTE_TAR="${BUILD_BRANCH}-${CMAKE_BUILD_TYPE}-llvm.tar"
CCACHE_REMOTE_TAR=$(echo "${CCACHE_REMOTE_TAR}" | tr 'A-Z' 'a-z')

rm -rf ".ccache"
cache_file="ccache.tar"
rm -rf "${cache_file}"
curl -o "${cache_file}" http://fileserver.pingcap.net/download/builds/pingcap/tiflash/ci-cache/${CCACHE_REMOTE_TAR}
cache_size=$(ls -l "${cache_file}" | awk '{print $5}')
min_size=$((1024000))
if [[ ${cache_size} -gt ${min_size} ]]; then
  echo "try to use ccache to accelerate compile speed"
  tar -xf ccache.tar
fi
ccache -o cache_dir=$(realpath .ccache)
ccache -o max_size=2G
ccache -o limit_multiple=0.99
ccache -o hash_dir=false
ccache -o compression=true
ccache -o compression_level=6
if [[ ${UPDATE_CCACHE} == "false" ]]; then
  ccache -o read_only=true
else
  ccache -o read_only=false
fi
ccache -z
'''
    container("builder") {
        dir("${cwd}/tics") {
            writeFile(file: 'prepare.sh', text: CI_PREPARE_SCRIPT)
            sh "env UPDATE_CCACHE=${params.UPDATE_CCACHE} CMAKE_BUILD_TYPE=${type} bash prepare.sh"
        }
    }   
}
    
def runWithCache(type, cwd) {
    stage("preparation") {
        prepareBuildCache(type, cwd)
    }
    stage("build") {
        container("builder") {
            dir("${cwd}/tics/build-${type}") {
                sh "cmake ${cwd}/tics -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_C_COMPILER=clang -DENABLE_TESTS=ON -DCMAKE_BUILD_TYPE=${type} -DUSE_CCACHE=ON -DRUN_HAVE_STD_REGEX=0 -DCMAKE_PREFIX_PATH=/usr/local -GNinja"
                sh "ninja gtests_dbms gtests_libcommon gtests_libdaemon"
            }
        }
    }
    stage("update cache") {
        def UPLOAD_CCACHE_SCRIPT='''
CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE:-Debug}
BUILD_BRANCH=${BUILD_BRANCH:-master}
CCACHE_REMOTE_TAR="${BUILD_BRANCH}-${CMAKE_BUILD_TYPE}-llvm.tar"
CCACHE_REMOTE_TAR=$(echo "${CCACHE_REMOTE_TAR}" | tr 'A-Z' 'a-z')
rm -rf ccache.tar
tar -cf ccache.tar .ccache
curl -F builds/pingcap/tiflash/ci-cache/${CCACHE_REMOTE_TAR}=@ccache.tar http://fileserver.pingcap.net/upload
        '''
        if (params.UPDATE_CCACHE) {
            container("builder") {
                dir("${cwd}/tics") {
                    writeFile(file: 'upload.sh', text: UPLOAD_CCACHE_SCRIPT)
                    sh "env CMAKE_BUILD_TYPE=${type} bash upload.sh"
                }
            }
        }
    }
    stage("run") {
        def RUN_SCRIPT='''
SRCPATH=${SRCPATH:-/tics}
CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE:-Debug}
NPROC=${NPROC:-$(nproc || grep -c ^processor /proc/cpuinfo)}
rm -rf /tests && ln -s ${SRCPATH}/tests /tests
rm -rf /tiflash && mkdir -p /tiflash
cp ${SRCPATH}/build-${CMAKE_BUILD_TYPE}/dbms/gtests_dbms /tiflash
cp ${SRCPATH}/build-${CMAKE_BUILD_TYPE}/libs/libcommon/src/tests/gtests_libcommon /tiflash
cp ${SRCPATH}/build-${CMAKE_BUILD_TYPE}/libs/libdaemon/src/tests/gtests_libdaemon /tiflash
source /tests/docker/util.sh
show_env
UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=0 ENV_VARS_PATH=/tests/docker/_env.sh NPROC=${NPROC} /tests/run-gtest.sh
        '''
        container("builder") {
            writeFile(file: 'run.sh', text: RUN_SCRIPT)
            sh "env CMAKE_BUILD_TYPE=${type} SRCPATH='${cwd}/tics' bash run.sh"
        }
    }
}


node("${GO_TEST_SLAVE}") {
    def result = "SUCCESS"
    def githash = null

    stage("Get Hash") {
        def target_branch = "master"
        echo "Target Branch: ${target_branch}"
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
        githash = sh(returnStdout: true, script: "python gethash.py -repo=tics -source=github -version=${target_branch} -s=${FILE_SERVER_URL}").trim()
    }
    
    try {
        def cwd = pwd()
        def sanitizer = params.SANITIZER
        runCheckoutAndBuilderClosure("tics-sanitizer-daily-regression-${sanitizer}", cwd) {
            runWithCache(sanitizer, cwd)
        }
    } catch (Exception e) {
        result = "FAILURE"
        echo "${e}"
    }
    
    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def msg = "Build Result: `${result}`" + "\n" +
                "Elapsed Time: `${duration} mins`" + "\n" +
                "${env.RUN_DISPLAY_URL}"
    
        echo "${msg}"
    
        if (result != "SUCCESS") {
            stage("sendLarkMessage") {
                def result_mark = "❌"
                def feishumsg = "tiflash-sanitizer-daily (${params.SANITIZER})\\n" +
                        "Build Number: ${env.BUILD_NUMBER}\\n" +
                        "Result: ${result} ${result_mark}\\n" +
                        "Git Hash: ${githash}\\n" +
                        "Elapsed Time: ${duration} Mins\\n" +
                        "Build Link: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-sanitizer-daily/detail/tiflash-sanitizer-daily/${env.BUILD_NUMBER}/pipeline\\n" +
                        "Job Page: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-sanitizer-daily/detail/tiflash-sanitizer-daily/activity/"
                print feishumsg
                node("master") {
                    withCredentials([string(credentialsId: 'tiflash-regression-lark-channel-hook', variable: 'TOKEN')]) {
                        sh """
                          curl -X POST ${TOKEN} -H 'Content-Type: application/json' \
                          -d '{
                            "msg_type": "text",
                            "content": {
                              "text": "$feishumsg"
                            }
                          }'
                        """
                    }
                }
            }
        }
    }
}