#include "data_crypto.hpp"

#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

bool readFile(const char* path, std::vector<std::uint8_t>& bytes) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        return false;
    }
    bytes.assign(std::istreambuf_iterator<char>(input),
                 std::istreambuf_iterator<char>());
    return input.good() || input.eof();
}

bool writeFile(const char* path, const std::vector<std::uint8_t>& bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        return false;
    }
    if (!bytes.empty()) {
        output.write(reinterpret_cast<const char*>(bytes.data()),
                     static_cast<std::streamsize>(bytes.size()));
    }
    output.flush();
    return output.good();
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 4 || argv[1] == nullptr || argv[2] == nullptr ||
        argv[3] == nullptr || argv[3][0] == '\0') {
        return 2;
    }

    std::vector<std::uint8_t> plaintext;
    if (!readFile(argv[1], plaintext) || plaintext.empty()) {
        return 3;
    }

    std::vector<std::uint8_t> encrypted;
    if (!DataCrypto::DecryptData(plaintext, argv[3], encrypted) ||
        encrypted.size() != plaintext.size()) {
        return 4;
    }

    return writeFile(argv[2], encrypted) ? 0 : 5;
}
